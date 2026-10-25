#!/usr/bin/env python3
"""
Souklab Full Artisanal Platform Seeder
Seeds rich, authentic Algerian artisanal data and high-quality real images into Souklab.
"""

import json
import mimetypes
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import subprocess
from datetime import datetime, timedelta

API_BASE = "http://localhost:8080/api/v1"
MAILPIT_API = "http://localhost:8025/api/v1"
ADMIN_EMAIL = "admin@souklab.dz"
ADMIN_PASSWORD = "Souklab-Admin-2026!"
DEFAULT_PASSWORD = "Demo-artisan-2026!"
CLIENT_PASSWORD = "Demo-client-2026!"

IMAGES_DIR = "/tmp/souklab_seed_images"
os.makedirs(IMAGES_DIR, exist_ok=True)

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
WIKIMEDIA_UA = "SouklabPlatform/1.0 (https://souklab.dz; contact@souklab.dz)"

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

def resolve_wikimedia_url(file_title):
    api_url = f"https://commons.wikimedia.org/w/api.php?action=query&titles={urllib.parse.quote(file_title)}&prop=imageinfo&iiprop=url&format=json"
    req = urllib.request.Request(api_url, headers={"User-Agent": WIKIMEDIA_UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                pages = data.get("query", {}).get("pages", {})
                for pid, page in pages.items():
                    if "imageinfo" in page:
                        return page["imageinfo"][0]["url"]
        except Exception as e:
            time.sleep(1)
    return None

def download_image(url, filename, is_wiki=False):
    target = os.path.join(IMAGES_DIR, filename)
    if os.path.exists(target) and os.path.getsize(target) > 5000:
        return target
    
    headers = {"User-Agent": WIKIMEDIA_UA if is_wiki else USER_AGENT}
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as resp:
                content = resp.read()
                if len(content) > 3000:
                    with open(target, "wb") as f:
                        f.write(content)
                    log(f"Downloaded {filename} ({len(content)} bytes)")
                    return target
        except Exception as e:
            time.sleep(1.5)
    
    # Fallback to local landing page image if download failed
    landing_fallback = "/home/walid/E/Coding/nextjs/souklab-frontend/frontend/public/assets/landing/hero-background.jpg"
    if os.path.exists(landing_fallback):
        with open(landing_fallback, "rb") as sf, open(target, "wb") as df:
            df.write(sf.read())
        log(f"Used local fallback for {filename}")
        return target
    return None

def api_call(method, endpoint, token=None, json_body=None):
    url = f"{API_BASE}{endpoint}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if json_body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(json_body).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status = resp.getcode()
            body_text = resp.read().decode("utf-8")
            res_json = json.loads(body_text) if body_text else None
            return status, res_json
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8")
        res_json = None
        try:
            res_json = json.loads(body_text)
        except Exception:
            pass
        return e.code, res_json
    except Exception as e:
        log(f"Error calling {method} {endpoint}: {e}")
        return 500, None

def upload_multipart(endpoint, token, file_path, field_name="file", query_params=None):
    url = f"{API_BASE}{endpoint}"
    if query_params:
        url += "?" + urllib.parse.urlencode(query_params)

    boundary = "----WebKitFormBoundary" + str(int(time.time() * 1000))
    mime_type, _ = mimetypes.guess_type(file_path)
    if not mime_type:
        mime_type = "image/jpeg"

    filename = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'
        f"Content-Type: {mime_type}\r\n\r\n"
    ).encode("utf-8") + file_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")

    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }

    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.getcode()
            body_text = resp.read().decode("utf-8")
            return status, json.loads(body_text) if body_text else None
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8")
        res_json = None
        try:
            res_json = json.loads(body_text)
        except Exception:
            pass
        log(f"Upload failed {endpoint}: {e.code} - {body_text}")
        return e.code, res_json

def get_mailpit_otp(email):
    for _ in range(12):
        try:
            search_url = f"{MAILPIT_API}/search?query={urllib.parse.quote(f'to:\"{email}\"')}"
            with urllib.request.urlopen(search_url, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                messages = data.get("messages", [])
                if messages:
                    msg_id = messages[0]["ID"]
                    msg_url = f"{MAILPIT_API}/message/{msg_id}"
                    with urllib.request.urlopen(msg_url, timeout=5) as mresp:
                        msg_data = json.loads(mresp.read().decode("utf-8"))
                        text = (msg_data.get("Text") or "") + " " + (msg_data.get("HTML") or "")
                        match = re.search(r"\b\d{6}\b", text)
                        if match:
                            return match.group(0)
        except Exception:
            pass
        time.sleep(1)
    return None

def login(email, password=DEFAULT_PASSWORD):
    status, res = api_call("POST", "/auth/login", json_body={"email": email, "password": password})
    if status == 200 and res and res.get("data"):
        return res["data"]["accessToken"], res["data"]["user"]
    raise Exception(f"Login failed for {email}: {status} {res}")

def run_db_query(sql):
    cmd = [
        "docker", "exec", "souklab_mariadb",
        "mariadb", "-usouklab_test", "-psouklab_test_password", "souklab_test",
        "-e", sql
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout

def db_scalar(sql):
    out = run_db_query(sql)
    lines = [l.strip() for l in out.strip().split("\n") if l.strip()]
    if len(lines) >= 2:
        return lines[1]
    return None

# --- 1. DOWNLOAD IMAGES ---
log("Starting download of real authentic artisanal images...")

IMAGE_SPECS = [
    # Avatars
    ("avatar_amina.jpg", "https://images.unsplash.com/photo-1544005313-94ddf0286df2?w=500&q=80", False),
    ("avatar_djamel.jpg", "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=500&q=80", False),
    ("avatar_yasmine.jpg", "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=500&q=80", False),
    ("avatar_mohamed.jpg", "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=500&q=80", False),
    ("avatar_meziane.jpg", "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?w=500&q=80", False),
    ("avatar_mustapha.jpg", "https://images.unsplash.com/photo-1519085360753-af0119f7cbe7?w=500&q=80", False),
    ("avatar_khadidja.jpg", "https://images.unsplash.com/photo-1580489944761-15a19d654956?w=500&q=80", False),
    ("avatar_tahar.jpg", "https://images.unsplash.com/photo-1506794778202-cad84cf45f1d?w=500&q=80", False),
    ("avatar_soraya.jpg", "https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?w=500&q=80", False),
    ("avatar_rachid.jpg", "https://images.unsplash.com/photo-1567532939604-b6b5b0db2604?w=500&q=80", False),
    ("avatar_sarah_client.jpg", "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=500&q=80", False),
    ("avatar_walid_client.jpg", "https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=500&q=80", False),
    
    # Pottery
    ("pottery_1.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/50/Kabyle_pottery_1.jpg", True),
    ("pottery_2.jpg", "https://images.unsplash.com/photo-1565193566173-7a0ee3dbe261?w=900&q=80", False),
    ("pottery_3.jpg", "https://images.unsplash.com/photo-1610701596007-11502861dcfa?w=900&q=80", False),
    ("pottery_4.jpg", "https://images.unsplash.com/photo-1578749556568-bc2c40e68b61?w=900&q=80", False),
    ("pottery_5.jpg", "https://images.unsplash.com/photo-1590736969955-71cc94801759?w=900&q=80", False),
    
    # Copperware
    ("copper_1.jpg", "https://images.unsplash.com/photo-1596178065887-1198b6148b2b?w=900&q=80", False),
    ("copper_2.jpg", "https://images.unsplash.com/photo-1541123437800-1bb1317badc2?w=900&q=80", False),
    ("copper_3.jpg", "https://images.unsplash.com/photo-1513519245088-0e12902e5a38?w=900&q=80", False),
    ("copper_4.jpg", "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=900&q=80", False),
    
    # Clothing / Embroidery / Karakou
    ("clothing_1.jpg", "https://upload.wikimedia.org/wikipedia/commons/e/ea/Algerian_Karakou.jpg", True),
    ("clothing_2.jpg", "https://images.unsplash.com/photo-1584917865442-de89df76afd3?w=900&q=80", False),
    ("clothing_3.jpg", "https://images.unsplash.com/photo-1600121848594-d8644e57abab?w=900&q=80", False),
    ("clothing_4.jpg", "https://images.unsplash.com/photo-1606760227091-3dd870d97f1d?w=900&q=80", False),
    
    # Zellige
    ("zellige_1.jpg", "https://upload.wikimedia.org/wikipedia/commons/1/10/Plaque_d%C3%A9corative_de_Zellige%2C_Tlemcen.jpg", True),
    ("zellige_2.jpg", "https://images.unsplash.com/photo-1548013146-72479768bada?w=900&q=80", False),
    ("zellige_3.jpg", "https://images.unsplash.com/photo-1564507592333-c60657eea523?w=900&q=80", False),
    
    # Jewelry
    ("jewelry_1.jpg", "https://upload.wikimedia.org/wikipedia/commons/1/1e/Bijoux_kabyles_tabzimit.jpg", True),
    ("jewelry_2.jpg", "https://images.unsplash.com/photo-1535632066927-ab7c9ab60908?w=900&q=80", False),
    ("jewelry_3.jpg", "https://images.unsplash.com/photo-1515562141207-7a88fb7ce338?w=900&q=80", False),
    ("jewelry_4.jpg", "https://images.unsplash.com/photo-1601121141461-9d6647bca1ed?w=900&q=80", False),
    
    # Earth / Pise
    ("earth_1.jpg", "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=900&q=80", False),
    ("earth_2.jpg", "https://images.unsplash.com/photo-1509316975850-ff9c5deb0cd9?w=900&q=80", False),
    
    # Leather
    ("leather_1.jpg", "https://images.unsplash.com/photo-1590874103328-eac38a683ce7?w=900&q=80", False),
    ("leather_2.jpg", "https://images.unsplash.com/photo-1544816155-12df9643f363?w=900&q=80", False),
    ("leather_3.jpg", "https://images.unsplash.com/photo-1553062407-98eeb64c6a62?w=900&q=80", False),
    
    # Woodworking
    ("wood_1.jpg", "https://images.unsplash.com/photo-1586023492125-27b2c045efd7?w=900&q=80", False),
    ("wood_2.jpg", "https://images.unsplash.com/photo-1540555700478-4be289fbecef?w=900&q=80", False),
    ("wood_3.jpg", "https://images.unsplash.com/photo-1538688525198-9b88f6f53126?w=900&q=80", False),
    
    # Stone Masonry
    ("stone_1.jpg", "https://images.unsplash.com/photo-1513694203232-719a280e022f?w=900&q=80", False),
    ("stone_2.jpg", "https://images.unsplash.com/photo-1582560475093-ba66accbc424?w=900&q=80", False),
]

for filename, url, is_wiki in IMAGE_SPECS:
    download_image(url, filename, is_wiki=is_wiki)

log("Image collection prepared.")

# Copy local landing images as additional backups
landing_dir = "/home/walid/E/Coding/nextjs/souklab-frontend/frontend/public/assets/landing"
for lf in os.listdir(landing_dir):
    src = os.path.join(landing_dir, lf)
    dst = os.path.join(IMAGES_DIR, f"landing_{lf}")
    if os.path.isfile(src) and not os.path.exists(dst):
        with open(src, "rb") as rf, open(dst, "wb") as wf:
            wf.write(rf.read())

# --- 2. ADMIN LOGIN & CATALOG LOOKUPS ---
log("Authenticating Administrator...")
admin_token, admin_user = login(ADMIN_EMAIL, ADMIN_PASSWORD)
log(f"Admin logged in successfully: {admin_user['email']}")

def fetch_catalog(name):
    _, res = api_call("GET", f"/catalog/{name}")
    return res.get("data", [])

regions_data = fetch_catalog("regions")
categories_data = fetch_catalog("categories")
materials_data = fetch_catalog("materials")
techniques_data = fetch_catalog("techniques")
epoques_data = fetch_catalog("epoques")

# Flatten regions (Wilayas)
wilayas = {}
for r in regions_data:
    for child in r.get("children", []):
        wilayas[child["name"].lower()] = child
        if child.get("code"):
            wilayas[child["code"]] = child

def find_wilaya(name_or_code):
    k = name_or_code.lower()
    if k in wilayas:
        return wilayas[k]
    for name, w in wilayas.items():
        if k in name or name in k:
            return w
    return list(wilayas.values())[0]

# Flatten subcategories
subcategories = {}
for c in categories_data:
    for sc in c.get("subCategories", []):
        subcategories[sc["slug"]] = sc
        subcategories[sc["name"].lower()] = sc

# Flatten materials
materials_by_name = {}
for mf in materials_data:
    for m in mf.get("materials", []):
        materials_by_name[m["name"].lower()] = m

# Flatten techniques
techniques_by_slug = {t["slug"]: t for t in techniques_data}

# Flatten epoques
epoques_by_slug = {e["slug"]: e for e in epoques_data}

log("Catalog lookup maps ready.")

# --- 3. ARTISANS DEFINITIONS ---
ARTISANS_DATA = [
    {
        "firstName": "Amina",
        "lastName": "Amraoui",
        "email": "amina.amraoui@souklab.dz",
        "wilaya": "Tizi Ouzou",
        "city": "Maâtkas",
        "subCategorySlug": "carreleur-mosaiste",
        "bio": "Héritière d'une longue lignée de potières de Maâtkas en Grande Kabylie, Amina perpétue un art millénaire transmis de mère en fille. Chaque jarre (ikoufanen), plat à couscous et cruche est façonné sans tour, poli au galet de rivière et orné de symboles géométriques berbères ancestraux racontant la fécondité, la terre et la vie kabyle.",
        "materials": ["Argile", "Mortier / chaux naturelle"],
        "techniques": ["zellij", "enduit-a-la-chaux"],
        "epoque": "periode-traditionnelle-pre-moderne",
        "avatar": "avatar_amina.jpg",
        "gallery": [
            ("pottery_1.jpg", "Jarre traditionnelle ikoufanen", "Poterie kabyle authentique façonnée à la main et cuite au feu de bois"),
            ("pottery_2.jpg", "Délicatesse des motifs géométriques", "Gros plan sur les engobes naturels rouge et noir"),
            ("pottery_3.jpg", "Modelage d'un plat tadjine", "Travail de l'argile locale sans tour de potier"),
            ("pottery_4.jpg", "Collection de bols et plats émaillés", "Poteries traditionnelles prêtes pour le séchage"),
        ],
        "certification": {
            "title": "Diplôme d'Honneur de Maître Potier",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tizi Ouzou",
            "issuedAt": "2014-06-15",
            "file": "pottery_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Djamel",
        "lastName": "Bensalem",
        "email": "djamel.bensalem@souklab.dz",
        "wilaya": "Constantine",
        "city": "Constantine",
        "subCategorySlug": "chaudronnier",
        "bio": "Maître dinandier installé au cœur de la Souika à Constantine depuis plus de 35 ans. Djamel martèle et cisèle le cuivre à la main pour créer des plateaux traditionnels (sinias), aiguières (brik), et cafetières mauresques ornées d'entrelacs floraux et d'arabesques arabo-andalouses d'une finesse incomparable.",
        "materials": ["Fer forgé", "Acier"],
        "techniques": ["maconnerie-en-moellons", "enduit-a-la-chaux"],
        "epoque": "epoque-ottomane",
        "avatar": "avatar_djamel.jpg",
        "gallery": [
            ("copper_1.jpg", "Vases et plateaux en cuivre rouge ciselé", "Pièces de prestige travaillées au burin dans la tradition constantinoise"),
            ("copper_2.jpg", "Martelage au tréteau", "Le geste précis du maître artisan sur feuille de laiton"),
            ("copper_3.jpg", "Détail d'arabesque mauresque", "Ciselure florale d'une sinia de mariage"),
            ("copper_4.jpg", "Service à thé traditionnel en cuivre", "Théière et verres étamés à chaud selon la tradition"),
        ],
        "certification": {
            "title": "Brevet de Maître Dinandier d'Art",
            "issuer": "Ministère du Tourisme et de l'Artisanat - CAM Constantine",
            "issuedAt": "2010-09-20",
            "file": "copper_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Yasmine",
        "lastName": "Bouziane",
        "email": "yasmine.bouziane@souklab.dz",
        "wilaya": "Alger",
        "city": "Casbah d'Alger",
        "subCategorySlug": "peintre-en-batiment",
        "bio": "Créatrice et restauratrice de costumes traditionnels algérois dans la haute Casbah d'Alger. Passionnée par la splendeur du Karakou et du Ghlila, Yasmine brode à l'aiguille sur velours de Gênes en utilisant les techniques séculaires du Majboud et du Chaâra. Ses créations allient authenticité patrimoniale et coupes contemporaines élégantes.",
        "materials": ["Enduits à la chaux", "Peintures acryliques"],
        "techniques": ["enduit-a-la-chaux", "gejij-platre-sculpte"],
        "epoque": "epoque-ottomane",
        "avatar": "avatar_yasmine.jpg",
        "gallery": [
            ("clothing_1.jpg", "Karakou algérois au fil d'or", "Veste de velours grenat brodée au Majboud avec serwal chelqa"),
            ("clothing_2.jpg", "Broderie Majboud au métier à tisser", "Détail du fil d'or pur cousu au point arrière"),
            ("clothing_3.jpg", "Tissage traditionnel de soie", "Étoffe soyeuse destinée aux cérémonies nuptiales"),
            ("clothing_4.jpg", "Chedda royale et parures d'apparat", "Ensemble traditionnel algérien de haute couture"),
        ],
        "certification": {
            "title": "Prix d'Excellence en Broderie Traditionnelle",
            "issuer": "Centre National de l'Artisanat Traditionnel d'Alger",
            "issuedAt": "2018-05-12",
            "file": "clothing_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Mohamed Redha",
        "lastName": "Khellafi",
        "email": "mohamed.khellafi@souklab.dz",
        "wilaya": "Tlemcen",
        "city": "Tlemcen",
        "subCategorySlug": "carreleur-mosaiste",
        "bio": "Héritier des maîtres faïenciers de la dynastie Zianide à Tlemcen, Mohamed Redha perpétue la taille manuelle du zellige. Chaque tesselle d'argile cuite et émaillée est façonnée avec une précision millimétrique pour composer rosaces, étoiles octogonales et frises murales inspirées des palais de Mechouar et d'El-Eubbad.",
        "materials": ["Carrelage / céramique", "Mosaïque", "Argile", "Mortier / chaux naturelle"],
        "techniques": ["zellij", "enduit-a-la-chaux"],
        "epoque": "periode-zianide",
        "avatar": "avatar_mohamed.jpg",
        "gallery": [
            ("zellige_1.jpg", "Plaque décorative de zellige zianide", "Motif étoilé polychrome en carreaux taillés main"),
            ("zellige_2.jpg", "Arc mauresque en zellige", "Restauration d'une cour intérieure de palais à Tlemcen"),
            ("zellige_3.jpg", "Frise géométrique complexe", "Composition minutieuse sans joint apparent"),
        ],
        "certification": {
            "title": "Certificat de Maîtrise en Restauration du Zellige",
            "issuer": "Direction de la Culture et du Patrimoine de Tlemcen",
            "issuedAt": "2015-11-04",
            "file": "zellige_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Meziane",
        "lastName": "Ait-Ouarab",
        "email": "meziane.aitouarab@souklab.dz",
        "wilaya": "Tizi Ouzou",
        "city": "Beni Yenni",
        "subCategorySlug": "ferronnier-d-art",
        "bio": "Installé dans le village perché de Beni Yenni, Meziane est l'un des rares orfèvres à maîtriser l'émaillage cloisonné et le sertissage de corail rouge brut de Méditerranée. Ses broches fibules (tabzimt), bracelets articulés (abzim) et colliers filigranés incarnent l'excellence de la joaillerie kabyle ancestrale.",
        "materials": ["Fer forgé", "Acier"],
        "techniques": ["maconnerie-en-moellons", "enduit-a-la-chaux"],
        "epoque": "periode-traditionnelle-pre-moderne",
        "avatar": "avatar_meziane.jpg",
        "gallery": [
            ("jewelry_1.jpg", "Tabzimt kabyle en argent et corail", "Grande fibule d'apparat ornée d'émaux cloisonnés jaune, vert et bleu"),
            ("jewelry_2.jpg", "Parure traditionnelle de Beni Yenni", "Bracelets ajourés et pendants d'oreilles filigranés"),
            ("jewelry_3.jpg", "Bagues et pendentifs artisanaux", "Sertissage soigné de cabochons de corail rouge naturel"),
            ("jewelry_4.jpg", "Établi de l'orfèvre", "Outils traditionnels de tréfilage et soudure à la flamme"),
        ],
        "certification": {
            "title": "Maître Artisan Orfèvre en Joaillerie Traditionnelle",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tizi Ouzou",
            "issuedAt": "2008-07-25",
            "file": "jewelry_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Mustapha",
        "lastName": "Touati",
        "email": "mustapha.touati@souklab.dz",
        "wilaya": "Ghardaïa",
        "city": "Ghardaïa",
        "subCategorySlug": "macon-du-patrimoine",
        "bio": "Bâtisseur traditionnel de la vallée du M'Zab, classée au patrimoine mondial de l'UNESCO. Mustapha maîtrise l'art de la construction bioclimatique en terre crue et chaux naturelle. Il intervient sur la restauration des ksour, des remparts historiques et la transmission des techniques sahariennes durables.",
        "materials": ["Terre crue (pisé, adobe, torchis)", "Mortier / chaux naturelle", "Pierre calcaire"],
        "techniques": ["pise", "enduit-a-la-chaux"],
        "epoque": "periode-rustumide-medievale",
        "avatar": "avatar_mustapha.jpg",
        "gallery": [
            ("earth_1.jpg", "Architecture bioclimatique du M'Zab", "Murs en pisé épais offrant une fraîcheur naturelle sous le soleil du désert"),
            ("earth_2.jpg", "Enduit traditionnel à la chaux blanche", "Finition soignée des terrasses et façades du ksar"),
            ("pottery_5.jpg", "Matériaux d'adobe et argile locale", "Préparation des mortiers écologiques"),
        ],
        "certification": {
            "title": "Brevet de Maîtrise en Architecture de Terre Crue",
            "issuer": "Office de Protection et de Promotion de la Vallée du M'Zab",
            "issuedAt": "2011-04-18",
            "file": "earth_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Khadidja",
        "lastName": "Belkacem",
        "email": "khadidja.belkacem@souklab.dz",
        "wilaya": "Médéa",
        "city": "Médéa",
        "subCategorySlug": "solier-moquettiste",
        "bio": "Dans son atelier artisanal de Médéa, Khadidja transforme les meilleurs cuirs d'Algérie selon les méthodes de tannage végétal. Elle confectionne à la main babouches de cérémonie, coussins d'apparat en cuir repoussé et sacs de voyage cousus au point sellier traditionnel.",
        "materials": ["Enduits à la chaux", "Carrelage / céramique"],
        "techniques": ["enduit-a-la-chaux"],
        "epoque": "periode-traditionnelle-pre-moderne",
        "avatar": "avatar_khadidja.jpg",
        "gallery": [
            ("leather_1.jpg", "Sac besace en cuir naturel tanné végétal", "Couture au point sellier à deux aiguilles et finitions cirées"),
            ("leather_2.jpg", "Outils de maroquinier d'art", "Alênes, gouges, abat-carre et fils de lin poissés"),
            ("leather_3.jpg", "Découpe du cuir pleine fleur", "Façonnage minutieux d'un porte-document traditionnel"),
        ],
        "certification": {
            "title": "Diplôme d'Artisanat d'Art en Maroquinerie",
            "issuer": "Chambre de l'Artisanat et des Métiers de Médéa",
            "issuedAt": "2016-09-10",
            "file": "leather_1.jpg",
        },
        "isTeacher": False,
    },
    {
        "firstName": "Tahar",
        "lastName": "Mansour",
        "email": "tahar.mansour@souklab.dz",
        "wilaya": "Blida",
        "city": "Blida",
        "subCategorySlug": "ebeniste",
        "bio": "Artisan ébéniste amoureux des essences nobles du massif de Chréa. Tahar réalise sur mesure des moucharabiehs ajourés, des plafonds sculptés à caissons et des coffres de cèdre odorant. Son travail protège l'intimité tout en laissant filtrer la lumière et la brise méditerranéenne.",
        "materials": ["Bois (pin, cyprès, chêne)", "Bois (pin, cyprès, chêne)"],
        "techniques": ["charpente-traditionnelle"],
        "epoque": "epoque-ottomane",
        "avatar": "avatar_tahar.jpg",
        "gallery": [
            ("wood_1.jpg", "Panneau de Moucharabieh en cèdre de l'Atlas", "Découpes géométriques ajourées pour baie intérieure"),
            ("wood_2.jpg", "Sculpture manuelle à la gouge", "Bas-relief végétal sur panneau de noyer massif"),
            ("wood_3.jpg", "Assemblage traditionnel à tenons et mortaises", "Finition huilée naturelle mettant en valeur le veinage"),
        ],
        "certification": {
            "title": "Premier Prix d'Ébénisterie et Sculpture sur Bois",
            "issuer": "Chambre de l'Artisanat et des Métiers de Blida",
            "issuedAt": "2019-10-30",
            "file": "wood_1.jpg",
        },
        "isTeacher": True,
    },
    {
        "firstName": "Soraya",
        "lastName": "Guellil",
        "email": "soraya.guellil@souklab.dz",
        "wilaya": "Batna",
        "city": "Batna",
        "subCategorySlug": "peintre-en-batiment",
        "bio": "Originaire des gorges du Ghoufi dans le massif de l'Aurès, Soraya tisse des tapis traditionnels de Babar réputés pour leur densité et leurs motifs géométriques chaouis chargés d'histoire. Chaque tapis est confectionné en pure laine vierge teinte aux pigments végétaux des Aurès.",
        "materials": ["Enduits à la chaux", "Mortier / chaux naturelle"],
        "techniques": ["enduit-a-la-chaux"],
        "epoque": "periode-numide",
        "avatar": "avatar_soraya.jpg",
        "gallery": [
            ("clothing_3.jpg", "Tapis chaoui des Aurès en pure laine", "Motifs losangiques traditionnels aux teintes ocre et garance"),
            ("clothing_2.jpg", "Travail sur métier vertical haute lice", "Nouage manuel de haute précision hérité des ancêtres chaouies"),
            ("clothing_4.jpg", "Fils de laine filés au fuseau", "Laine naturelle teinte à l'écorce de noyer et à la garance"),
        ],
        "certification": {
            "title": "Certificat de Maîtrise en Tissage Traditionnel",
            "issuer": "Chambre de l'Artisanat et des Métiers de Batna",
            "issuedAt": "2013-05-22",
            "file": "clothing_3.jpg",
        },
        "isTeacher": False,
    },
    {
        "firstName": "Rachid",
        "lastName": "Zemmouri",
        "email": "rachid.zemmouri@souklab.dz",
        "wilaya": "Tipaza",
        "city": "Tipaza",
        "subCategorySlug": "tailleur-de-pierre",
        "bio": "Tailleur de pierre et restaurateur du patrimoine monumental en bordure de la Méditerranée à Tipaza. Rachid travaille la pierre calcaire coquillière et le marbre pour la restauration de bâtisses anciennes, fontaines publiques, arcs en plein cintre et linteaux sculptés dans le respect de l'artisanat d'époque.",
        "materials": ["Pierre calcaire", "Marbre", "Mortier / chaux naturelle"],
        "techniques": ["taille-de-pierre", "maconnerie-en-moellons", "enduit-a-la-chaux"],
        "epoque": "periode-numide",
        "avatar": "avatar_rachid.jpg",
        "gallery": [
            ("stone_1.jpg", "Taille d'un arc en plein cintre en calcaire", "Ajustement millimétrique des claveaux de pierre"),
            ("stone_2.jpg", "Restauration d'un mur historique à Tipaza", "Moellons équarris montés au mortier de chaux aérienne"),
            ("earth_1.jpg", "Vasque de fontaine taillée dans la masse", "Finitions douces bouchardées résistant aux embruns"),
        ],
        "certification": {
            "title": "Diplôme Supérieur de Tailleur de Pierre du Patrimoine",
            "issuer": "École Nationale des Métiers du Patrimoine Historique",
            "issuedAt": "2007-06-18",
            "file": "stone_1.jpg",
        },
        "isTeacher": True,
    },
]

# --- 4. SEED ARTISANS ---
log("Starting artisan seeding process...")
tokens = {}
artisan_ids = {}

for art in ARTISANS_DATA:
    email = art["email"]
    log(f"Processing artisan: {art['firstName']} {art['lastName']} ({email})")
    
    # Check if user already exists
    login_ok = False
    try:
        tok, udata = login(email)
        tokens[email] = tok
        artisan_ids[email] = udata["id"]
        login_ok = True
        log(f"  User {email} already exists and logged in.")
    except Exception:
        pass

    if not login_ok:
        # Register user
        reg_body = {
            "accountType": "ARTISAN",
            "firstName": art["firstName"],
            "lastName": art["lastName"],
            "email": email,
            "password": DEFAULT_PASSWORD,
        }
        st, res = api_call("POST", "/auth/register", json_body=reg_body)
        if st not in (200, 201, 409):
            log(f"  Failed to register {email}: {st} {res}")
            continue
        
        # Verify email OTP
        otp = get_mailpit_otp(email)
        if otp:
            st, vres = api_call("POST", "/auth/verify-email", json_body={"email": email, "code": otp})
            log(f"  Verified email with OTP {otp}: status {st}")
        else:
            # Fallback direct DB update for test reliability
            run_db_query(f"UPDATE users SET email_verified = 1, email_verified_at = NOW(), status = 'PENDING' WHERE email = '{email}';")
            log(f"  Set verified directly in DB for {email}")

        # Approve by Admin
        pending_st, pending_res = api_call("GET", "/admin/users/pending?size=100", token=admin_token)
        user_id = None
        if pending_st == 200 and pending_res and pending_res.get("data"):
            items = pending_res["data"].get("content", [])
            for item in items:
                if item.get("email") == email:
                    user_id = item["id"]
                    break
        
        if user_id:
            api_call("POST", f"/admin/users/{user_id}/approve", token=admin_token)
            log(f"  Approved by Admin: user_id {user_id}")
        else:
            run_db_query(f"UPDATE users SET status = 'ACTIVE' WHERE email = '{email}';")

        tok, udata = login(email)
        tokens[email] = tok
        artisan_ids[email] = udata["id"]

    token = tokens[email]
    uid = artisan_ids[email]

    # Resolve Catalog IDs
    wil = find_wilaya(art["wilaya"])
    subcat = subcategories.get(art["subCategorySlug"])
    mat_ids = [materials_by_name[m.lower()]["id"] for m in art["materials"] if m.lower() in materials_by_name]
    tech_ids = [techniques_by_slug[t]["id"] for t in art["techniques"] if t in techniques_by_slug]
    epoque_id = epoques_by_slug.get(art["epoque"], {}).get("id")

    profile_body = {
        "city": art["city"],
        "bio": art["bio"],
        "regionId": wil["id"],
        "subCategoryId": subcat["id"] if subcat else None,
        "materialIds": mat_ids,
        "techniqueIds": tech_ids,
        "epoqueIds": [epoque_id] if epoque_id else [],
    }
    st_prof, res_prof = api_call("POST", "/auth/complete-profile", token=token, json_body=profile_body)
    log(f"  Profile updated (status {st_prof})")

    # Upload Avatar
    avatar_file = os.path.join(IMAGES_DIR, art["avatar"])
    if os.path.exists(avatar_file):
        av_st, av_res = upload_multipart("/users/me/avatars", token, avatar_file)
        log(f"  Avatar uploaded: status {av_st}")

    # Upload Gallery Images
    gal_count = db_scalar(f"SELECT count(*) FROM artisan_gallery_images WHERE artisan_id = '{uid}';")
    if not gal_count or gal_count == "0":
        for gfile, gtitle, gcap in art["gallery"]:
            gpath = os.path.join(IMAGES_DIR, gfile)
            if os.path.exists(gpath):
                gst, gres = upload_multipart("/artisan/gallery", token, gpath, query_params={"title": gtitle, "caption": gcap})
                log(f"    Gallery image uploaded '{gtitle}': {gst}")
    else:
        log(f"    Gallery images already present for {art['firstName']} {art['lastName']}")

    # Upload Certification
    cert_count = db_scalar(f"SELECT count(*) FROM artisan_certifications WHERE artisan_id = '{uid}';")
    if not cert_count or cert_count == "0":
        cert = art["certification"]
        cpath = os.path.join(IMAGES_DIR, cert["file"])
        if os.path.exists(cpath):
            cst, cres = upload_multipart(
                "/artisan/certifications",
                token,
                cpath,
                query_params={"title": cert["title"], "issuer": cert["issuer"], "issuedAt": cert["issuedAt"]}
            )
            log(f"    Certification uploaded '{cert['title']}': {cst}")
    else:
        log(f"    Certification already present for {art['firstName']} {art['lastName']}")

    # Grant Teacher Status if Formateur
    if art.get("isTeacher"):
        api_call("POST", f"/admin/artisans/{uid}/formateur-grant", token=admin_token, json_body={"adminNote": "Formateur accrédité."})
        log(f"    Formateur accreditation granted.")

log("Artisans seeded successfully.")

# Also update existing demo artisans with avatars and gallery if needed
for demo_email, demo_avatar in [
    ("karim.haddad@demo.souklab.test", "pottery_2.jpg"),
    ("nadia.benali@demo.souklab.test", "clothing_2.jpg"),
    ("yacine.mansouri@demo.souklab.test", "earth_2.jpg"),
    ("sophie.martin@demo.souklab.test", "stone_2.jpg"),
    ("amina.kaci@demo.souklab.test", "zellige_2.jpg"),
]:
    try:
        dtok, dudata = login(demo_email)
        tokens[demo_email] = dtok
        artisan_ids[demo_email] = dudata["id"]
        av_path = os.path.join(IMAGES_DIR, demo_avatar)
        if os.path.exists(av_path):
            upload_multipart("/users/me/avatars", dtok, av_path)
            log(f"Updated avatar for demo artisan {demo_email}")
    except Exception as e:
        log(f"Demo artisan {demo_email} update: {e}")

# --- 5. SEED FORMATIONS (MASTERCLASSES) ---
log("Seeding masterclasses (Formations)...")

def in_days(days, hour=9):
    dt = datetime.now() + timedelta(days=days)
    return f"{dt.strftime('%Y-%m-%d')}T{hour:02d}:00:00"

FORMATIONS_DATA = [
    {
        "formateurEmail": "amina.amraoui@souklab.dz",
        "thumbnail": "pottery_2.jpg",
        "body": {
            "title": "Atelier Poterie Berbère : Modelage Ancestral et Décor aux Engobes",
            "description": "Une immersion complète d'une journée dans la poterie traditionnelle kabyle à Maâtkas.\n\nVous apprendrez à préparer l'argile locale, à monter une pièce au colombin sans tour selon la méthode ancestrale des femmes de Maâtkas, puis à peindre les motifs géométriques berbères avec des engobes minéraux naturels. Chaque participant repart avec sa création cuite.",
            "location": "Atelier familial d'Ighil Bouchene, Maâtkas, Tizi Ouzou",
            "isOnline": False,
            "scheduledAt": in_days(14, 9),
            "durationHours": 7,
            "maxParticipants": 8,
            "price": 6500,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "djamel.bensalem@souklab.dz",
        "thumbnail": "copper_2.jpg",
        "body": {
            "title": "Maîtrise de la Ciselure sur Cuivre et Gravure de Motifs Mauresques",
            "description": "Venez découvrir les secrets de la dinanderie constantinoise dans l'un des plus anciens ateliers de la Souika.\n\nApprenez le maniement du marteau de ciseleur, l'utilisation des burins et traçoirs sur plaque de cuivre rouge, et la gravure d'une sinia (plateau) aux motifs d'arabesques florales arabo-andalouses.",
            "location": "Atelier de la Souika, Vieille Ville de Constantine",
            "isOnline": False,
            "scheduledAt": in_days(20, 10),
            "durationHours": 8,
            "maxParticipants": 6,
            "price": 9000,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "yasmine.bouziane@souklab.dz",
        "thumbnail": "clothing_1.jpg",
        "body": {
            "title": "Initiation à la Broderie d'Or Algéroise (Majboud & Fetla)",
            "description": "Stage intensif de deux jours au cœur de la Casbah d'Alger.\n\nApprenez la technique séculaire du Majboud sur métier à broder en bois (guerguaf) : pose du fil d'or couché au point arrière, travail sur velours de soie, et création d'un motif traditionnel de col de Karakou. Matériel et fournitures d'or fournis.",
            "location": "Palais des Raïs (Bastion 23), Casbah d'Alger",
            "isOnline": False,
            "scheduledAt": in_days(12, 9),
            "durationHours": 12,
            "maxParticipants": 6,
            "price": 12000,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "mohamed.khellafi@souklab.dz",
        "thumbnail": "zellige_1.jpg",
        "body": {
            "title": "Art du Zellige : Découpe Manuelle et Pose Géométrique",
            "description": "Découvrez l'art du zellige arabo-andalou zianide.\n\nLes participants apprendront à utiliser le marteau de tailleur (menqach) pour débiter les carreaux émaillés en formes géométriques précises (étoiles, saoussan, chevrons), puis à assembler un panneau décoratif inversé au mortier de chaux.",
            "location": "Atelier El-Mechouar, Centre artisanal de Tlemcen",
            "isOnline": False,
            "scheduledAt": in_days(18, 9),
            "durationHours": 7,
            "maxParticipants": 8,
            "price": 8500,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "meziane.aitouarab@souklab.dz",
        "thumbnail": "jewelry_1.jpg",
        "body": {
            "title": "Filigrane d'Argent et Sertissage de Corail de Beni Yenni",
            "description": "Une journée exceptionnelle dans la capitale de l'argenterie berbère.\n\nInitiation au tréfilage et torsadage des fils d'argent 925, réalisation d'une broche fibule traditionnelle (tabzimt), initiation à la cuisson des émaux vitrifiés et sertissage d'une perle de corail rouge brut de Méditerranée.",
            "location": "Village artisanal de Beni Yenni, Grande Kabylie",
            "isOnline": False,
            "scheduledAt": in_days(25, 9),
            "durationHours": 10,
            "maxParticipants": 5,
            "price": 14000,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "mustapha.touati@souklab.dz",
        "thumbnail": "earth_1.jpg",
        "body": {
            "title": "Construction en Terre Crue et Pisé Bioclimatique",
            "description": "Atelier pratique sur chantier réel dans la vallée du M'Zab.\n\nApprentissage du dosage de la terre, confection de briques d'adobe séchées au soleil saharien, montage d'un mur en pisé damé au pilon et application de l'enduit protecteur naturel en chaux et argile blanche.",
            "location": "Ksar de Beni Isguen, Vallée du M'Zab",
            "isOnline": False,
            "scheduledAt": in_days(30, 8),
            "durationHours": 14,
            "maxParticipants": 10,
            "price": 10000,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "tahar.mansour@souklab.dz",
        "thumbnail": "wood_1.jpg",
        "body": {
            "title": "Fabrication d'un Moucharabieh Traditionnel en Cèdre de l'Atlas",
            "description": "Atelier d'ébénisterie d'art dédié à l'art du moucharabieh.\n\nApprenez le tournage des fuseaux en cèdre de l'Atlas, l'entaillage et l'assemblage géométrique à tenons et mortaises sans quincaillerie métallique. Repartez avec votre panneau décoratif ajouré.",
            "location": "Atelier du Cèdre, Route de Chréa, Blida",
            "isOnline": False,
            "scheduledAt": in_days(16, 9),
            "durationHours": 8,
            "maxParticipants": 6,
            "price": 11000,
            "currency": "DZD",
        }
    },
    {
        "formateurEmail": "rachid.zemmouri@souklab.dz",
        "thumbnail": "stone_1.jpg",
        "body": {
            "title": "Taille de Pierre et Restauration des Voûtes Anciennes",
            "description": "Découverte des gestes du tailleur de pierre sur pierre calcaire méditerranéenne.\n\nÉpannelage, dressage des faces à la broche et au ciseau, traçage d'une moulure et taille d'un voussoir d'arc en plein cintre.",
            "location": "Atelier Maritime, Port de Tipaza",
            "isOnline": False,
            "scheduledAt": in_days(22, 9),
            "durationHours": 7,
            "maxParticipants": 8,
            "price": 7500,
            "currency": "DZD",
        }
    },
]

formation_ids = {}

for form in FORMATIONS_DATA:
    f_email = form["formateurEmail"]
    f_token = tokens.get(f_email)
    if not f_token:
        continue

    form_title = form["body"]["title"]
    safe_title = form_title.replace("'", "''")
    existing_fid = db_scalar(f"SELECT id FROM formations WHERE title = '{safe_title}';")
    if existing_fid:
        formation_ids[form_title] = existing_fid
        log(f"Formation already exists: '{form_title}' ({existing_fid})")
        continue

    # Create formation
    st_cr, res_cr = api_call("POST", "/artisan/formations", token=f_token, json_body=form["body"])
    if st_cr not in (200, 201) or not res_cr.get("data"):
        log(f"Formation creation failed: {st_cr} {res_cr}")
        continue
    
    fid = res_cr["data"]["id"]
    formation_ids[form["body"]["title"]] = fid
    log(f"Created Formation '{form['body']['title']}': {fid}")

    # Upload thumbnail
    t_file = os.path.join(IMAGES_DIR, form["thumbnail"])
    if os.path.exists(t_file):
        upload_multipart(f"/artisan/formations/{fid}/thumbnail", f_token, t_file)
        log(f"  Thumbnail uploaded for formation {fid}")

    # Submit -> Admin Review -> Publish
    api_call("POST", f"/artisan/formations/{fid}/submit", token=f_token)
    api_call("POST", f"/admin/formations/{fid}/review", token=admin_token, json_body={"decision": "APPROVED"})
    api_call("POST", f"/admin/formations/{fid}/publish", token=admin_token)
    log(f"  Approved and published formation {fid}")

# --- 6. SEED COMPLETED FORMATIONS & VERIFIED REVIEWS ---
log("Setting up completed masterclasses and verified participant reviews...")

# We will enroll some artisans into past masterclasses and add reviews
REVIEW_PAIRS = [
    {
        "formationTitle": "Atelier Poterie Berbère : Modelage Ancestral et Décor aux Engobes",
        "formateurEmail": "amina.amraoui@souklab.dz",
        "participantEmail": "yasmine.bouziane@souklab.dz",
        "rating": 5,
        "comment": "Une journée inoubliable avec Amina ! Sa passion pour la terre et les symboles berbères de Kabylie est communicative. J'ai adoré travailler l'argile sans tour et polir ma jarre au galet. Je recommande les yeux fermés !"
    },
    {
        "formationTitle": "Maîtrise de la Ciselure sur Cuivre et Gravure de Motifs Mauresques",
        "formateurEmail": "djamel.bensalem@souklab.dz",
        "participantEmail": "meziane.aitouarab@souklab.dz",
        "rating": 5,
        "comment": "Maître Djamel est une sommité de la dinanderie. Sa pédagogie et sa maîtrise du burin à Constantine sont remarquables. En tant que bijoutier, j'ai énormément appris sur les motifs arabo-andalous."
    },
    {
        "formationTitle": "Initiation à la Broderie d'Or Algéroise (Majboud & Fetla)",
        "formateurEmail": "yasmine.bouziane@souklab.dz",
        "participantEmail": "amina.amraoui@souklab.dz",
        "rating": 5,
        "comment": "Deux journées magiques dans la Casbah d'Alger. Yasmine transmet le Majboud avec une infinie patience et une grande précision. Mon col de Karakou est magnifique !"
    },
    {
        "formationTitle": "Art du Zellige : Découpe Manuelle et Pose Géométrique",
        "formateurEmail": "mohamed.khellafi@souklab.dz",
        "participantEmail": "mustapha.touati@souklab.dz",
        "rating": 5,
        "comment": "Formation exceptionnelle au Mechouar de Tlemcen. La taille au menqach demande du doigté mais les explications de Mohamed Redha sont limpides. Un vrai enrichissement professionnel."
    },
]

for rev in REVIEW_PAIRS:
    fid = formation_ids.get(rev["formationTitle"])
    part_tok = tokens.get(rev["participantEmail"])
    form_tok = tokens.get(rev["formateurEmail"])
    if not fid or not part_tok or not form_tok:
        continue

    # Enroll participant
    st_enr, res_enr = api_call("POST", f"/artisan/formations/{fid}/enroll", token=part_tok)
    enr_id = None
    if st_enr in (200, 201) and res_enr and res_enr.get("data"):
        enr_id = res_enr["data"]["id"]
    else:
        # Check if already enrolled
        st_mine, res_mine = api_call("GET", "/artisan/formations/enrollments/me", token=part_tok)
        if st_mine == 200 and res_mine and res_mine.get("data"):
            for item in res_mine["data"].get("content", []):
                if item.get("formationId") == fid:
                    enr_id = item["id"]
                    break

    # Move date back in database to allow completion
    run_db_query(f"UPDATE formations SET scheduled_at = UTC_TIMESTAMP() - INTERVAL 5 DAY WHERE id = '{fid}';")

    if enr_id:
        # Mark attendance ATTENDED
        api_call("PUT", f"/artisan/formations/{fid}/attendance/{enr_id}", token=form_tok, json_body={"status": "ATTENDED"})
    
    # Mark formation COMPLETED
    run_db_query(f"UPDATE formations SET status = 'COMPLETED' WHERE id = '{fid}';")

    # Submit review
    st_rev, res_rev = api_call("POST", f"/artisan/formations/{fid}/reviews", token=part_tok, json_body={
        "rating": rev["rating"],
        "comment": rev["comment"]
    })
    log(f"Submitted review on '{rev['formationTitle']}': status {st_rev}")

# --- 7. SEED SOCIAL FEED POSTS ---
log("Seeding authentic social feed posts with real photos...")

FEED_POSTS = [
    {
        "authorEmail": "amina.amraoui@souklab.dz",
        "type": "ACTUALITE",
        "title": "Cuisson au feu de bois terminée pour nos jarres de Maâtkas",
        "body": "Après trois jours de séchage au soleil printanier de Kabylie et 10 heures de cuisson dans notre four traditionnel en plein air, nos jarres (ikoufanen) et plats à couscous révèlent leurs teintes ocre et leurs motifs symboliques.\n\nChaque pièce porte l'empreinte de notre terre et de nos ancêtres. Merci à tous pour votre soutien continu !",
        "photos": ["pottery_1.jpg", "pottery_2.jpg"],
        "tags": ["poterie", "kabylie", "algerie", "artisanat", "faitmain"]
    },
    {
        "authorEmail": "djamel.bensalem@souklab.dz",
        "type": "ACTUALITE",
        "title": "Achèvement d'une grande sinia constantinoise de 90 cm de diamètre",
        "body": "Plus de deux mois de ciselure minutieuse au burin se concluent aujourd'hui dans mon atelier de la Souika à Constantine.\n\nCette grande sinia en cuivre rouge massif est ornée d'entrelacs floraux inspirés de l'époque ottomane et arabo-andalouse. Elle rejoindra le trousseau traditionnel d'une future mariée.",
        "photos": ["copper_1.jpg", "copper_3.jpg"],
        "tags": ["dinanderie", "constantine", "cuivre", "patrimoine", "algerie"]
    },
    {
        "authorEmail": "yasmine.bouziane@souklab.dz",
        "type": "ACTUALITE",
        "title": "Le Karakou 'Nour d'Alger' brodé au Majboud sur velours de soie",
        "body": "Un travail d'orfèvre sur velours de soie bordeaux : plus de 180 heures de broderie à l'aiguille au fil d'or pur.\n\nLes arabesques et palmettes sont directement inspirées des stucs mauresques du Palais des Raïs (Bastion 23). Fière de voir notre patrimoine vestimentaire algérois continuer de briller !",
        "photos": ["clothing_1.jpg", "clothing_2.jpg"],
        "tags": ["karakou", "alger", "broderie", "majboud", "caftan", "haute_couture"]
    },
    {
        "authorEmail": "mohamed.khellafi@souklab.dz",
        "type": "ACTUALITE",
        "title": "Restauration d'une cour intérieure en Zellige traditionnel à Tlemcen",
        "body": "Fin de chantier pour la restauration de ce patio historique à Tlemcen.\n\nChaque tesselle verte, safran et bleu nuit a été taillée manuellement au marteau menqach selon les règles zianides du XIVe siècle. La géométrie sacrée reprend toute sa splendeur sous la lumière méditerranéenne.",
        "photos": ["zellige_1.jpg", "zellige_2.jpg"],
        "tags": ["zellige", "tlemcen", "mosaique", "patrimoine", "architecture"]
    },
    {
        "authorEmail": "meziane.aitouarab@souklab.dz",
        "type": "ACTUALITE",
        "title": "Nouvelle parure Tabzimt en argent 925 et corail rouge d'El Kala",
        "body": "Arrivage de magnifiques branches de corail rouge brut ce matin à Beni Yenni. Voici la dernière fibule tabzimt sortie de l'établi, avec ses émaux vitrifiés vert émeraude et jaune safran cuits au four à moufle.\n\nUn trésor de nos montagnes du Djurdjura.",
        "photos": ["jewelry_1.jpg", "jewelry_3.jpg"],
        "tags": ["bijoux", "kabylie", "beniyenni", "argent", "corail"]
    },
    {
        "authorEmail": "tahar.mansour@souklab.dz",
        "type": "ACTUALITE",
        "title": "Portes monumentales en cèdre sculpté pour une demeure de Blida",
        "body": "Assemblage des vantaux en cèdre de l'Atlas terminé ! Les assemblages à tenons et mortaises ont été réalisés sans clou métallique, puis nourris à la cire d'abeille naturelle.\n\nLe parfum du bois embaume tout l'atelier de Chréa.",
        "photos": ["wood_1.jpg", "wood_2.jpg"],
        "tags": ["ebenisterie", "bois", "moucharabieh", "sculpture", "blida"]
    },
    {
        "authorEmail": "mustapha.touati@souklab.dz",
        "type": "ANNONCE",
        "title": "Chantier école : réhabilitation d'un ksar en terre crue dans le M'Zab",
        "body": "Notre association lance un chantier participatif le mois prochain pour consolider les remparts de Beni Isguen.\n\nOuvert aux passionnés d'éco-construction, d'architecture vernaculaire et de bâti saharien. Venez apprendre le pisé et les enduits au timchent sur le terrain !",
        "photos": ["earth_1.jpg", "earth_2.jpg"],
        "tags": ["pise", "terrecrue", "ghardaia", "mzab", "architecture"]
    },
    {
        "authorEmail": "rachid.zemmouri@souklab.dz",
        "type": "ACTUALITE",
        "title": "Taille d'une vasque de fontaine en pierre calcaire de Tipaza",
        "body": "Derniers coups de boucharde sur cette fontaine octogonale destinée à une place publique.\n\nLa pierre calcaire locale acquiert une belle patine dorée avec le temps et résiste admirablement aux embruns marins.",
        "photos": ["stone_1.jpg", "stone_2.jpg"],
        "tags": ["tailledepierre", "tipaza", "maconnerie", "pierre", "patrimoine"]
    },
]

post_ids = []

for post in FEED_POSTS:
    auth_email = post["authorEmail"]
    auth_token = tokens.get(auth_email)
    if not auth_token:
        continue

    post_title = post["title"]
    safe_title = post_title.replace("'", "''")
    existing_pid = db_scalar(f"SELECT id FROM feed_posts WHERE title = '{safe_title}';")
    if existing_pid:
        post_ids.append(existing_pid)
        log(f"Feed post already exists: '{post_title}' ({existing_pid})")
        continue

    p_body = {
        "type": post["type"],
        "title": post["title"],
        "body": post["body"],
    }
    st_p, res_p = api_call("POST", "/feed", token=auth_token, json_body=p_body)
    if st_p not in (200, 201) or not res_p.get("data"):
        log(f"Feed post creation failed: {st_p} {res_p}")
        continue
    
    pid = res_p["data"]["id"]
    post_ids.append(pid)
    log(f"Created Feed Post '{post['title']}': {pid}")

    # Upload Media Photos
    for pphoto in post["photos"]:
        ppath = os.path.join(IMAGES_DIR, pphoto)
        if os.path.exists(ppath):
            upload_multipart(f"/feed/{pid}/media", auth_token, ppath)
            log(f"  Media photo uploaded: {pphoto}")

    # Admin Publishes Post
    api_call("POST", f"/admin/feed/{pid}/publish", token=admin_token, json_body={"note": "Publication validée."})
    log(f"  Post published by admin: {pid}")

# --- 8. SOCIAL INTERACTIONS (LIKES & COMMENTS) ---
log("Adding realistic social engagement (likes, comments, bookmarks)...")

COMMENTS_POOL = [
    ("djamel.bensalem@souklab.dz", "Un travail remarquable ! Tout mon respect pour la préservation de ce savoir-faire."),
    ("yasmine.bouziane@souklab.dz", "Magnifique réalisation, les détails sont d'une finesse incroyable !"),
    ("amina.amraoui@souklab.dz", "Bravo ! On ressent toute l'âme de nos traditions à travers cette œuvre."),
    ("mohamed.khellafi@souklab.dz", "Splendide harmonie des formes et des couleurs. Félicitations maître !"),
    ("meziane.aitouarab@souklab.dz", "Du grand art artisanal ! Fierté pour notre patrimoine national."),
]

all_artisan_tokens = list(tokens.values())

for i, pid in enumerate(post_ids):
    # Add likes from 3-5 artisans
    for tok in all_artisan_tokens[:4]:
        api_call("POST", f"/feed/{pid}/likes", token=tok)
    
    # Add 1-2 comments
    c_author, c_text = COMMENTS_POOL[i % len(COMMENTS_POOL)]
    c_token = tokens.get(c_author)
    if c_token:
        api_call("POST", f"/feed/{pid}/comments", token=c_token, json_body={"content": c_text})

log("Social engagement seeded.")

# --- 9. CLIENT USERS & FAVORITES ---
log("Seeding client accounts and favorites...")

CLIENTS_DATA = [
    {
        "firstName": "Sarah",
        "lastName": "Chérif",
        "email": "sarah.cherif@souklab.dz",
        "avatar": "avatar_sarah_client.jpg",
    },
    {
        "firstName": "Walid",
        "lastName": "Amari",
        "email": "walid.client@souklab.dz",
        "avatar": "avatar_walid_client.jpg",
    },
    {
        "firstName": "Claire",
        "lastName": "Démo",
        "email": "client.demo@demo.souklab.test",
        "avatar": "avatar_amina.jpg",
    },
]

CLIENT_PERMISSION = "permission:client:favorites"

for cl in CLIENTS_DATA:
    cemail = cl["email"]
    log(f"Processing client: {cemail}")
    cl_tok = None
    try:
        cl_tok, cuser = login(cemail, CLIENT_PASSWORD)
    except Exception:
        pass

    if not cl_tok:
        reg_body = {
            "accountType": "CLIENT",
            "firstName": cl["firstName"],
            "lastName": cl["lastName"],
            "email": cemail,
            "password": CLIENT_PASSWORD,
        }
        st_c, _ = api_call("POST", "/auth/register", json_body=reg_body)
        otp = get_mailpit_otp(cemail)
        if otp:
            api_call("POST", "/auth/verify-email", json_body={"email": cemail, "code": otp})
        else:
            run_db_query(f"UPDATE users SET email_verified = 1, email_verified_at = NOW(), status = 'ACTIVE' WHERE email = '{cemail}';")
        
        try:
            cl_tok, cuser = login(cemail, CLIENT_PASSWORD)
        except Exception as e:
            log(f"Client login error {cemail}: {e}")
            continue

    # Grant client permission if needed
    st_me, res_me = api_call("GET", "/auth/me", token=cl_tok)
    if st_me == 200 and res_me and res_me.get("data"):
        user_data = res_me["data"]
        perms = user_data.get("permissions", [])
        if CLIENT_PERMISSION not in perms:
            api_call("POST", f"/admin/users/{user_data['id']}/permissions", token=admin_token, json_body={"permissionKey": CLIENT_PERMISSION})

        # Upload client avatar
        c_av = os.path.join(IMAGES_DIR, cl["avatar"])
        if os.path.exists(c_av):
            upload_multipart("/users/me/avatars", cl_tok, c_av)

    # Favorite 3 artisans
    for fav_artisan_email in ["amina.amraoui@souklab.dz", "djamel.bensalem@souklab.dz", "yasmine.bouziane@souklab.dz", "meziane.aitouarab@souklab.dz"]:
        fav_id = artisan_ids.get(fav_artisan_email)
        if not fav_id:
            fav_id = db_scalar(f"SELECT id FROM users WHERE email = '{fav_artisan_email}';")
        if fav_id:
            st_f, res_f = api_call("POST", f"/client/favorites/artisans/{fav_id}", token=cl_tok)
            log(f"  Favorited artisan {fav_artisan_email} for {cemail}: status {st_f}")

log("Client accounts and favorites seeded.")

# --- 10. REINDEX ELASTICSEARCH ---
log("Triggering mass sync for Elasticsearch...")
try:
    log("Restarting backend container to execute Hibernate Search mass indexing on startup...")
    subprocess.run(["docker", "compose", "-f", "/home/walid/E/Coding/nextjs/souklab-frontend/docker-compose.yml", "restart", "backend"], check=True)
    log("Waiting for backend container to become ready...")
    for _ in range(45):
        time.sleep(2)
        try:
            req = urllib.request.Request("http://localhost:8080/api/v1/catalog/categories")
            with urllib.request.urlopen(req, timeout=3) as resp:
                if resp.getcode() == 200:
                    log("Backend is online and ready!")
                    break
        except Exception:
            pass
    active_count = db_scalar("SELECT count(*) FROM artisans WHERE deleted_at IS NULL;")
    log(f"Database active artisans: {active_count}")
except Exception as e:
    log(f"Reindex notice: {e}")

log("=== SOUKLAB ARTISANAL DATA SEEDING COMPLETE ===")
