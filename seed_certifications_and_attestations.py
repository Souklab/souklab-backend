#!/usr/bin/env python3
"""
Souklab Certifications & Attestations Seeder
Generates authentic official Algerian certificates and attestations (PDF & image)
and seeds them for Artisans and Clients across the Souklab platform.
"""

import json
import mimetypes
import os
import math
import socket
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime, date

from reportlab.lib.pagesizes import A4, landscape
from reportlab.pdfgen import canvas
from reportlab.lib import colors

# Force IPv4
_orig_getaddrinfo = socket.getaddrinfo
def _ipv4_getaddrinfo(*args, **kwargs):
    res = _orig_getaddrinfo(*args, **kwargs)
    ipv4_res = [r for r in res if r[0] == socket.AF_INET]
    return ipv4_res if ipv4_res else res
socket.getaddrinfo = _ipv4_getaddrinfo

API_BASE = "http://localhost:8080/api/v1"
ADMIN_EMAIL = "admin@souklab.dz"
ADMIN_PASSWORD = "Souklab-Admin-2026!"
DEFAULT_PASSWORD = "Demo-artisan-2026!"
CLIENT_PASSWORD = "Demo-client-2026!"

CERT_DIR = "/tmp/souklab_certificates"
os.makedirs(CERT_DIR, exist_ok=True)

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

def sql_escape(val):
    if val is None:
        return ""
    return str(val).replace("'", "''")

def db_exec(sql):
    cmd = [
        "docker", "exec", "souklab_mariadb",
        "mariadb", "-sN", "-u", "souklab_test", "-psouklab_test_password",
        "-D", "souklab_test", "-e", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        log(f"DB Error: {res.stderr.strip()}")
    return res.stdout.strip()

def db_scalar_int(sql, default=0):
    val = db_exec(sql)
    try:
        first_token = val.split()[0] if val.split() else "0"
        return int(first_token)
    except Exception:
        return default

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
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.getcode()
            body_text = resp.read().decode("utf-8")
            return status, json.loads(body_text) if body_text else None
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8")
        try:
            return e.code, json.loads(body_text)
        except Exception:
            return e.code, None
    except Exception as e:
        log(f"Error {method} {endpoint}: {e}")
        return 500, None

def login(email, password):
    st, res = api_call("POST", "/auth/login", json_body={"email": email, "password": password})
    if st == 200 and res and res.get("data"):
        return res["data"]["accessToken"], res["data"]["user"]["id"]
    return None, None

def upload_multipart(endpoint, token, file_path, query_params=None):
    url = f"{API_BASE}{endpoint}"
    if query_params:
        url += "?" + urllib.parse.urlencode(query_params)

    boundary = "----WebKitFormBoundary" + str(int(time.time() * 1000))
    mime_type, _ = mimetypes.guess_type(file_path)
    if not mime_type:
        mime_type = "application/pdf" if file_path.endswith(".pdf") else "image/jpeg"

    filename = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
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
            return resp.getcode(), json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, None
    except Exception as e:
        log(f"Upload error: {e}")
        return 500, None

# ------------------------------------------------------------------------------
# REPORTLAB OFFICIAL ALGERIAN CERTIFICATE GENERATOR
# ------------------------------------------------------------------------------

def draw_star(c, cx, cy, r, color):
    c.saveState()
    c.setFillColor(color)
    c.setStrokeColor(color)
    p = c.beginPath()
    for i in range(10):
        angle = i * math.pi / 5 - math.pi / 2
        radius = r if i % 2 == 0 else r * 0.4
        x = cx + radius * math.cos(angle)
        y = cy + radius * math.sin(angle)
        if i == 0:
            p.moveTo(x, y)
        else:
            p.lineTo(x, y)
    p.close()
    c.drawPath(p, fill=1, stroke=0)
    c.restoreState()

def draw_seal(c, cx, cy, radius, seal_label="CHAMBRE DE L'ARTISANAT"):
    c.saveState()
    # Outer gold ring
    c.setStrokeColor(colors.HexColor('#C89B3C'))
    c.setLineWidth(2.5)
    c.circle(cx, cy, radius, stroke=1, fill=0)
    
    # Inner dark emerald ring
    c.setLineWidth(1)
    c.setStrokeColor(colors.HexColor('#0A4D33'))
    c.circle(cx, cy, radius - 4, stroke=1, fill=0)

    # Cream center
    c.setFillColor(colors.HexColor('#F8F5EE'))
    c.circle(cx, cy, radius - 6, stroke=0, fill=1)

    # Red Algerian Crescent & Star
    c.setFillColor(colors.HexColor('#D32F2F'))
    c.setStrokeColor(colors.HexColor('#D32F2F'))
    c.circle(cx - 2, cy + 2, radius * 0.28, stroke=0, fill=1)
    c.setFillColor(colors.HexColor('#F8F5EE'))
    c.circle(cx + 3, cy + 3, radius * 0.24, stroke=0, fill=1)
    
    draw_star(c, cx + 5, cy + 3, radius * 0.12, colors.HexColor('#D32F2F'))

    # Circular text
    c.setFont("Helvetica-Bold", 5.5)
    c.setFillColor(colors.HexColor('#0A4D33'))
    c.drawCentredString(cx, cy - radius + 7, "RÉPUBLIQUE ALGÉRIENNE")
    c.drawCentredString(cx, cy + radius - 11, seal_label)
    c.restoreState()

def generate_pdf_certificate(filename, title, recipient, craft_or_description, issuer, issued_at, credential_id, is_attestation=False):
    filepath = os.path.join(CERT_DIR, filename)
    w, h = landscape(A4)
    c = canvas.Canvas(filepath, pagesize=landscape(A4))
    c.setTitle(title)
    c.setAuthor("Souklab - Métiers d'Art & Patrimoine")

    # Paper tint
    c.setFillColor(colors.HexColor('#FCFAF6'))
    c.rect(0, 0, w, h, fill=1, stroke=0)

    # Geometric Borders (Algerian National Green & Warm Gold)
    c.setStrokeColor(colors.HexColor('#0A4D33'))
    c.setLineWidth(4.5)
    c.rect(22, 22, w - 44, h - 44, stroke=1, fill=0)

    c.setStrokeColor(colors.HexColor('#C89B3C'))
    c.setLineWidth(1.8)
    c.rect(30, 30, w - 60, h - 60, stroke=1, fill=0)

    c.setStrokeColor(colors.HexColor('#0A4D33'))
    c.setLineWidth(0.7)
    c.rect(34, 34, w - 68, h - 68, stroke=1, fill=0)

    # Ornate 8-pointed star florets at corners
    for x, y in [(34, 34), (w - 34, 34), (34, h - 34), (w - 34, h - 34)]:
        draw_star(c, x, y, 7, colors.HexColor('#C89B3C'))
        draw_star(c, x, y, 3.5, colors.HexColor('#0A4D33'))

    # Republic Header
    c.setFont("Helvetica-Bold", 10.5)
    c.setFillColor(colors.HexColor('#0A4D33'))
    c.drawCentredString(w / 2, h - 58, "الجمهورية الجزائرية الديمقراطية الشعبية")
    c.drawCentredString(w / 2, h - 72, "RÉPUBLIQUE ALGÉRIENNE DÉMOCRATIQUE ET POPULAIRE")
    
    c.setFont("Helvetica", 9)
    c.setFillColor(colors.HexColor('#4B5563'))
    c.drawCentredString(w / 2, h - 86, "MINISTÈRE DU TOURISME ET DE L'ARTISANAT")

    c.setFont("Helvetica-Bold", 9.5)
    c.setFillColor(colors.HexColor('#C89B3C'))
    c.drawCentredString(w / 2, h - 100, issuer.upper())

    # Decorative header divider
    c.setStrokeColor(colors.HexColor('#C89B3C'))
    c.setLineWidth(1.2)
    c.line(w / 2 - 220, h - 110, w / 2 + 220, h - 110)
    draw_star(c, w / 2, h - 110, 5, colors.HexColor('#0A4D33'))

    # Certificate Title
    c.setFont("Helvetica-Bold", 21)
    c.setFillColor(colors.HexColor('#0A4D33'))
    c.drawCentredString(w / 2, h - 146, title.upper())

    # Intro phrasing
    c.setFont("Helvetica-Oblique", 11)
    c.setFillColor(colors.HexColor('#4B5563'))
    if is_attestation:
        intro = "La direction de la formation atteste officiellement que l'atelier de qualification a été suivi avec succès par :"
    else:
        intro = "En vertu des délibérations de la Commission des Qualifications Artisanales et du Patrimoine, décerné à :"
    c.drawCentredString(w / 2, h - 180, intro)

    # Recipient Name (Prominent & Elegant)
    c.setFont("Helvetica-Bold", 24)
    c.setFillColor(colors.HexColor('#111827'))
    c.drawCentredString(w / 2, h - 216, recipient)

    # Golden underline under recipient
    c.setStrokeColor(colors.HexColor('#C89B3C'))
    c.setLineWidth(1.8)
    tw = c.stringWidth(recipient, "Helvetica-Bold", 24)
    c.line(w / 2 - tw / 2 - 25, h - 224, w / 2 + tw / 2 + 25, h - 224)

    # Body description
    c.setFont("Helvetica", 11.5)
    c.setFillColor(colors.HexColor('#374151'))
    if is_attestation:
        c.drawCentredString(w / 2, h - 252, "Pour son assiduité remarquable et la maîtrise complète des compétences pratiques en :")
    else:
        c.drawCentredString(w / 2, h - 252, "En reconnaissance de la maîtrise exemplaire du savoir-faire traditionnel et de l'excellence en :")

    c.setFont("Helvetica-Bold", 13.5)
    c.setFillColor(colors.HexColor('#0A4D33'))
    c.drawCentredString(w / 2, h - 275, craft_or_description)

    # Legal mention & Registration ID
    c.setFont("Helvetica-Oblique", 8.5)
    c.setFillColor(colors.HexColor('#6B7280'))
    c.drawCentredString(w / 2, h - 308, "Enregistré aux registres nationaux de l'artisanat d'art et du patrimoine vivant • Décret exécutif n° 97-141")

    c.setFont("Helvetica-Bold", 9)
    c.setFillColor(colors.HexColor('#0A4D33'))
    c.drawCentredString(w / 2, h - 324, f"N° d'Accréditation : {credential_id}   •   Délivré le : {issued_at}")

    # Official Seals and Signatures
    draw_seal(c, 115, 96, 40, "SOUKLAB ACCRÉDITATION" if is_attestation else "CHAMBRE DE L'ARTISANAT")

    # Left signatory
    c.setFont("Helvetica-Bold", 9)
    c.setFillColor(colors.HexColor('#1F2937'))
    c.drawString(195, 115, "Le Directeur de la Chambre")
    c.drawString(195, 102, "de l'Artisanat et des Métiers")
    c.setFont("Helvetica-Oblique", 8)
    c.setFillColor(colors.HexColor('#6B7280'))
    c.drawString(195, 84, "[Signé et scellé officiellement]")

    # Right signatory
    c.setFont("Helvetica-Bold", 9)
    c.setFillColor(colors.HexColor('#1F2937'))
    c.drawRightString(w - 90, 115, "Le Maître Formateur Référent")
    c.drawRightString(w - 90, 102, "& Président du Jury d'Évaluation")
    c.setFont("Helvetica-Oblique", 8)
    c.setFillColor(colors.HexColor('#6B7280'))
    c.drawRightString(w - 90, 84, "[Signature & Cachet d'État]")

    # Bottom watermark line
    c.setFont("Helvetica", 7.5)
    c.setFillColor(colors.HexColor('#9CA3AF'))
    c.drawCentredString(w / 2, 42, "Document officiel numérisé et certifié conforme sur la plateforme Souklab • Identifiant sécurisé https://souklab.dz")

    c.save()
    return filepath

# ------------------------------------------------------------------------------
# DATA DEFINITIONS
# ------------------------------------------------------------------------------

ARTISAN_CERTIFICATIONS = {
    "amina.amraoui@souklab.dz": [
        {
            "title": "Brevet de Maîtrise Artisanale (BMA) - Poterie Traditionnelle",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tizi Ouzou",
            "issuedAt": "2015-06-15",
            "credentialId": "CAM-15-BMA-2015-0842",
            "craft": "Poterie Berbère Façonnée Main & Décor aux Engobes Minéraux",
            "filename": "cert_amina_bma.pdf"
        },
        {
            "title": "Diplôme de Maître Artisan d'Art",
            "issuer": "Ministère du Tourisme et de l'Artisanat",
            "issuedAt": "2019-11-20",
            "credentialId": "MTA-ALG-DMA-2019-0118",
            "craft": "Transmission du Savoir-Faire Ancestral Féminin de Maâtkas",
            "filename": "cert_amina_dma.pdf"
        },
        {
            "title": "Label National d'Authenticité et de Qualité de l'Artisanat Algérien",
            "issuer": "Commission Nationale de Labellisation Artisanale",
            "issuedAt": "2022-04-10",
            "credentialId": "LQA-ALG-2022-0091",
            "craft": "Ikoufanen et Céramiques Traditionnelles de Kabylie",
            "filename": "cert_amina_label.pdf"
        }
    ],
    "djamel.bensalem@souklab.dz": [
        {
            "title": "Titre Officiel de Maître Dinandier d'Art de Constantine",
            "issuer": "Chambre de l'Artisanat et des Métiers de Constantine",
            "issuedAt": "2012-05-18",
            "credentialId": "CAM-25-MDA-2012-0019",
            "craft": "Martelage, Rétreinte et Ciselure sur Cuivre et Bronze",
            "filename": "cert_djamel_mda.pdf"
        },
        {
            "title": "Brevet Professionnel Supérieur - Ciselure de Motifs Mauresques",
            "issuer": "Institut National des Métiers d'Art et du Patrimoine",
            "issuedAt": "2017-09-25",
            "credentialId": "INMA-CST-BPS-2017-0034",
            "craft": "Arabesques Arabo-Andalouses et Plateaux Traditionnels (Sinias)",
            "filename": "cert_djamel_bps.pdf"
        },
        {
            "title": "Reconnaissance d'Excellence Métiers du Patrimoine Vivant",
            "issuer": "Ministère de la Culture et des Arts",
            "issuedAt": "2021-03-14",
            "credentialId": "MCA-PV-2021-0083",
            "craft": "Sauvegarde et Transmission de la Dinanderie de la Souika",
            "filename": "cert_djamel_patrimoine.pdf"
        }
    ],
    "meziane.aitouarab@souklab.dz": [
        {
            "title": "Diplôme de Maître Artisan Bijoutier-Joaillier en Argenterie",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tizi Ouzou",
            "issuedAt": "2014-04-12",
            "credentialId": "CAM-15-MAB-2014-0067",
            "craft": "Bijouterie Traditionnelle en Argent Émaillé de Beni Yenni",
            "filename": "cert_meziane_mab.pdf"
        },
        {
            "title": "Certificat de Savoir-Faire Ancestral - Filigrane et Corail Rouge",
            "issuer": "Ministère du Tourisme et de l'Artisanat",
            "issuedAt": "2018-08-30",
            "credentialId": "MTA-SFA-2018-0012",
            "craft": "Tréfilage d'Argent Pur, Cloisonné et Sertissage de Corail",
            "filename": "cert_meziane_filigrane.pdf"
        }
    ],
    "soraya.guellil@souklab.dz": [
        {
            "title": "Brevet de Maîtrise - Tissage Traditionnel et Teintures Naturelles",
            "issuer": "Chambre de l'Artisanat et des Métiers de Batna",
            "issuedAt": "2016-07-22",
            "credentialId": "CAM-05-BMA-2016-0055",
            "craft": "Tapis Berbères Chaouis en Pure Laine Vierge des Aurès",
            "filename": "cert_soraya_bma.pdf"
        },
        {
            "title": "Label Qualité Artisanat Algérien - Tapis des Aurès",
            "issuer": "Commission Nationale du Label de Qualité",
            "issuedAt": "2020-10-15",
            "credentialId": "LQA-BTN-2020-0077",
            "craft": "Tissage à Haute Lisse et Motifs Géométriques Aurésiens",
            "filename": "cert_soraya_label.pdf"
        }
    ],
    "yasmine.bouziane@souklab.dz": [
        {
            "title": "Diplôme Supérieur de Maître Brodeuse d'Art et Haute Couture",
            "issuer": "Chambre de l'Artisanat et des Métiers d'Alger",
            "issuedAt": "2013-09-10",
            "credentialId": "CAM-16-DSB-2013-0098",
            "craft": "Broderie d'Or Algéroise, Karakou et Costumes Traditionnels",
            "filename": "cert_yasmine_dsb.pdf"
        },
        {
            "title": "Certificat d'Excellence Patrimoniale - Majboud et Fetla",
            "issuer": "Commission Nationale de Sauvegarde du Costume Algérien",
            "issuedAt": "2019-12-05",
            "credentialId": "CNSCA-EXP-2019-0145",
            "craft": "Points Ancestraux sur Velours de Soie et Fil d'Or Fin",
            "filename": "cert_yasmine_majboud.pdf"
        }
    ],
    "tahar.mansour@souklab.dz": [
        {
            "title": "Titre Officiel de Maître Ébéniste d'Art et Sculpteur sur Bois",
            "issuer": "Chambre de l'Artisanat et des Métiers de Blida",
            "issuedAt": "2011-03-20",
            "credentialId": "CAM-09-MEA-2011-0023",
            "craft": "Sculpture et Tournage d'Art sur Cèdre de l'Atlas",
            "filename": "cert_tahar_mea.pdf"
        },
        {
            "title": "Certificat d'Habilitation Bâtiments Historiques et Monuments",
            "issuer": "Agence Nationale des Secteurs Sauvegardés (ANSS)",
            "issuedAt": "2017-06-18",
            "credentialId": "ANSS-HAB-2017-0049",
            "craft": "Restauration de Plafonds Peints, Moucharabiehs et Boiseries Anciennes",
            "filename": "cert_tahar_anss.pdf"
        }
    ],
    "rachid.zemmouri@souklab.dz": [
        {
            "title": "Brevet de Maîtrise - Taille de Pierre Calcaire et Stéréotomie",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tipaza",
            "issuedAt": "2014-11-14",
            "credentialId": "CAM-42-BMP-2014-0031",
            "craft": "Taille Manuelle de Pierre de Taille et Voûtes Sarrasines",
            "filename": "cert_rachid_bmp.pdf"
        },
        {
            "title": "Accréditation Restaurateur de Sites Antiques et Monuments",
            "issuer": "Office National de Gestion des Biens Culturels Protégés (OGEBC)",
            "issuedAt": "2018-05-22",
            "credentialId": "OGEBC-ACC-2018-0062",
            "craft": "Restauration Archéologique et Consolidation Monumentale",
            "filename": "cert_rachid_ogebc.pdf"
        }
    ],
    "mustapha.touati@souklab.dz": [
        {
            "title": "Maître Bâtisseur en Terre Crue et Chaux Aérienne du Ksar",
            "issuer": "Chambre de l'Artisanat et des Métiers de Ghardaïa",
            "issuedAt": "2010-02-15",
            "credentialId": "CAM-47-MBC-2010-0015",
            "craft": "Architecture Vernaculaire Saharienne, Pisé et Confection d'Adobes",
            "filename": "cert_mustapha_mbc.pdf"
        },
        {
            "title": "Prix National de Préservation de l'Architecture Vernaculaire",
            "issuer": "Fondation du Patrimoine M'Zab / Conseil du Patrimoine",
            "issuedAt": "2018-03-28",
            "credentialId": "FPM-PRX-2018-0008",
            "craft": "Enduits Hydrofuges à la Chaux Aérienne et Bioclimatisme Mozabite",
            "filename": "cert_mustapha_prix.pdf"
        }
    ],
    "khadidja.belkacem@souklab.dz": [
        {
            "title": "Diplôme de Maître Artisan Maroquinier et Gainier d'Art",
            "issuer": "Chambre de l'Artisanat et des Métiers de Médéa",
            "issuedAt": "2015-10-08",
            "credentialId": "CAM-26-MAM-2015-0088",
            "craft": "Travail Traditionnel du Cuir Plein Fleur au Tannage Végétal",
            "filename": "cert_khadidja_mam.pdf"
        },
        {
            "title": "Certificat d'Artisan d'Élite - Cuirs Façonnés et Reliure Mauresque",
            "issuer": "Fédération Nationale de l'Artisanat Traditionnel",
            "issuedAt": "2020-09-17",
            "credentialId": "FNAT-CAE-2020-0041",
            "craft": "Ciselure sur Cuir, Dorure à Chaud et Sellerie d'Art",
            "filename": "cert_khadidja_elite.pdf"
        }
    ],
    "mohamed.khellafi@souklab.dz": [
        {
            "title": "Brevet de Maître Carreleur Zelligeur et Mosaïste d'Art",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tlemcen",
            "issuedAt": "2012-12-04",
            "credentialId": "CAM-13-BMZ-2012-0004",
            "craft": "Découpe Manuelle au Menqach et Pose Géométrique de Zellige",
            "filename": "cert_mohamed_bmz.pdf"
        },
        {
            "title": "Label d'Excellence Art Islamique et Architecture Zellige",
            "issuer": "Centre d'Études Andalouses de Tlemcen",
            "issuedAt": "2016-08-19",
            "credentialId": "CEAT-ZEL-2016-0092",
            "craft": "Restauration de Fontaines et Mosaïques Morales Arabe-Andalouses",
            "filename": "cert_mohamed_ceat.pdf"
        }
    ],
    "karim.haddad@demo.souklab.test": [
        {
            "title": "Brevet de Maîtrise - Maçonnerie Traditionnelle et Enduits à la Chaux",
            "issuer": "Chambre de l'Artisanat et des Métiers de Tizi Ouzou",
            "issuedAt": "2016-09-12",
            "credentialId": "CAM-15-BMA-2016-0102",
            "craft": "Restauration de Maisons Kabyles en Pierre Sèche et Chaux",
            "filename": "cert_karim_bm.pdf"
        }
    ],
    "sophie.martin@demo.souklab.test": [
        {
            "title": "Diplôme Supérieur des Métiers d'Art - Taille de Pierre et Restauration",
            "issuer": "École Nationale Supérieure des Métiers d'Art",
            "issuedAt": "2017-06-25",
            "credentialId": "ENSMA-DP-2017-0055",
            "craft": "Diagnostic Monumental et Restauration de Façades Anciennes",
            "filename": "cert_sophie_dma.pdf"
        }
    ],
    "nadia.benali@demo.souklab.test": [
        {
            "title": "Brevet d'Artisan d'Art - Broderie et Confection Traditionnelle",
            "issuer": "Chambre de l'Artisanat et des Métiers d'Alger",
            "issuedAt": "2018-05-14",
            "credentialId": "CAM-16-BAA-2018-0073",
            "craft": "Confection de Costumes Patrimoniaux et Dentelle au Fuseau",
            "filename": "cert_nadia_baa.pdf"
        }
    ],
    "yacine.mansouri@demo.souklab.test": [
        {
            "title": "Certificat d'Aptitude Professionnelle - Bâtisse en Terre Crue",
            "issuer": "Chambre de l'Artisanat et des Métiers de Ghardaïa",
            "issuedAt": "2019-04-18",
            "credentialId": "CAM-47-CAP-2019-0029",
            "craft": "Mise en Œuvre du Pisé et des Briques d'Adobe",
            "filename": "cert_yacine_cap.pdf"
        }
    ],
    # --------------------------------------------------------------------------
    # CLIENTS (Attestations de Formation & Diplômes)
    # --------------------------------------------------------------------------
    "sarah.cherif@souklab.dz": [
        {
            "title": "Attestation de Réussite : Atelier de Poterie Berbère Traditionnelle",
            "issuer": "Atelier Maître Artisane Amina Amraoui & Souklab",
            "issuedAt": "2026-10-21",
            "credentialId": "SOUKLAB-ATTEST-2026-0104",
            "craft": "Perfectionnement au Façonnage Sans Tour et Décor aux Engobes Naturels",
            "filename": "attest_sarah_poterie.pdf",
            "isAttestation": True
        },
        {
            "title": "Certificat de Participation : Broderie d'Or Algéroise (Majboud & Fetla)",
            "issuer": "Maison de Broderie Maître Yasmine Bouziane & Souklab",
            "issuedAt": "2026-10-21",
            "credentialId": "SOUKLAB-ATTEST-2026-0219",
            "craft": "Maîtrise des Points Traditionnels sur Velours et Montage de Fils d'Or",
            "filename": "attest_sarah_broderie.pdf",
            "isAttestation": True
        },
        {
            "title": "Diplôme d'Études en Architecture d'Intérieur et Patrimoine Artisanal",
            "issuer": "École Polytechnique d'Architecture et d'Urbanisme (EPAU)",
            "issuedAt": "2023-07-05",
            "credentialId": "EPAU-ARC-2023-0418",
            "craft": "Intégration des Matériaux et Savoir-Faire Traditionnels dans l'Habitat Contemporain",
            "filename": "cert_sarah_epau.pdf"
        }
    ],
    "walid.client@souklab.dz": [
        {
            "title": "Attestation de Stage Professionnel : Taille de Pierre Calcaire du Patrimoine",
            "issuer": "Atelier Maître Rachid Zemmouri & CAM de Tipaza",
            "issuedAt": "2026-10-21",
            "credentialId": "SOUKLAB-ATTEST-2026-0312",
            "craft": "Techniques de Stéréotomie et Débitage de la Pierre Calcaire Ancienne",
            "filename": "attest_walid_pierre.pdf",
            "isAttestation": True
        },
        {
            "title": "Certificat de Réussite : Architecture Vernaculaire et Bâtisse en Terre Crue",
            "issuer": "Atelier Maître Mustapha Touati & Fondation du Patrimoine M'Zab",
            "issuedAt": "2026-10-21",
            "credentialId": "SOUKLAB-ATTEST-2026-0455",
            "craft": "Confection d'Adobes et Enduits Hydrofuges à la Chaux Aérienne",
            "filename": "attest_walid_terre.pdf",
            "isAttestation": True
        },
        {
            "title": "Titre d'Architecte Spécialiste de la Restauration du Bâti Ancien",
            "issuer": "Ordre National des Architectes (ONA)",
            "issuedAt": "2021-11-18",
            "credentialId": "ONA-ARCH-2021-0089",
            "craft": "Diagnostic Monumental et Réhabilitation des Structures Méditerranéennes",
            "filename": "cert_walid_ona.pdf"
        }
    ]
}

def main():
    log("Starting comprehensive certification & attestation generation and seeding...")
    admin_token, _ = login(ADMIN_EMAIL, ADMIN_PASSWORD)
    if not admin_token:
        log("Failed to login as admin!")
        return

    # 1. Setup client accounts (Sarah Chérif and Walid Amari) with full permissions, artisan learner profiles, and premium subscriptions
    for client_email, password, first, last, client_type in [
        ("sarah.cherif@souklab.dz", CLIENT_PASSWORD, "Sarah", "Chérif", "INDIVIDUAL"),
        ("walid.client@souklab.dz", CLIENT_PASSWORD, "Walid", "Amari", "BUSINESS"),
    ]:
        token, uid = login(client_email, password)
        if not uid:
            log(f"Logging in {client_email} failed!")
            continue

        log(f"Configuring client account: {client_email} ({uid})")

        # Ensure artisan record exists
        art_count = db_scalar_int(f"SELECT count(*) FROM artisans WHERE id = '{uid}';")
        if art_count == 0:
            db_exec(f"""
                INSERT INTO artisans (id, bio, city, address, website, region_id, sub_category_id, is_teacher, is_verified, is_premium, rating, response_rate, reviews_count, views_count, created_at, updated_at)
                VALUES ('{uid}', 'Membre actif passionné par la valorisation du patrimoine artisanal algérien.', 'Alger', NULL, NULL, NULL, NULL, 0, 1, 1, 5.0, 100, 0, 0, NOW(), NOW())
                ON DUPLICATE KEY UPDATE updated_at = NOW();
            """)
            log(f"  Inserted artisan record for {client_email}")

        # Ensure client record is marked premium
        db_exec(f"UPDATE clients SET is_premium = 1, is_verified = 1, client_type = '{client_type}' WHERE id = '{uid}';")

        # Grant necessary permissions
        perms_to_grant = [
            "permission:client:favorites",
            "permission:artisan:content",
            "permission:artisan:formations",
            "permission:profile:read",
            "permission:profile:write",
            "permission:file:read",
            "permission:message:send",
            "permission:report:create"
        ]
        for p in perms_to_grant:
            db_exec(f"""
                INSERT IGNORE INTO user_permissions (user_id, permission_id)
                SELECT '{uid}', id FROM permissions WHERE permission_key = '{p}';
            """)

        # Ensure active subscription in client_subscriptions
        sub_count = db_scalar_int(f"SELECT count(*) FROM client_subscriptions WHERE account_id = '{uid}' AND status = 'ACTIVE';")
        if sub_count == 0:
            sub_id = f"sub-client-{uid[:8]}"
            db_exec(f"""
                INSERT INTO client_subscriptions (id, account_id, plan_id, plan_name, billing_period, amount, currency, status, starts_at, expires_at, entitlements_snapshot, version_number, created_at, updated_at)
                VALUES ('{sub_id}', '{uid}', '262daef6-7ded-42d7-9a84-cdf979b0bb3c', 'Premium Client Plan', 'MONTHLY', 1500, 'DZD', 'ACTIVE', NOW() - INTERVAL 5 DAY, NOW() + INTERVAL 25 DAY, '{{"contact_info":"true","chat":"true"}}', 1, NOW(), NOW());
            """)
            log(f"  Enrolled in active Premium subscription for {client_email}")

    # 2. Seed formation enrollments for Sarah and Walid in completed masterclasses
    # Formations:
    # f7501339-5279-4835-a0fd-eafba609b30d (Poterie Berbère - Amina)
    # 0f8c74ee-1ec9-4e9c-924c-0e81605b70dd (Broderie d'Or - Yasmine)
    # 73332d73-accb-433c-888f-7b6fd7be8e23 (Tissage Tapis - Soraya)
    # 0869e1ee-6d4e-4fde-90e7-6fb139dffbda (Taille de Pierre - Rachid)
    # 7322e61f-4aa5-4fd0-8545-c330bcfe3b60 (Terre Crue - Mustapha)
    # e237f258-0226-4807-9924-2510f07b96dc (Zellige - Mohamed Redha)

    sarah_id = db_exec("SELECT id FROM users WHERE email = 'sarah.cherif@souklab.dz';").strip()
    walid_id = db_exec("SELECT id FROM users WHERE email = 'walid.client@souklab.dz';").strip()

    client_enrollments = [
        (sarah_id, "f7501339-5279-4835-a0fd-eafba609b30d"), # Poterie
        (sarah_id, "0f8c74ee-1ec9-4e9c-924c-0e81605b70dd"), # Broderie
        (sarah_id, "73332d73-accb-433c-888f-7b6fd7be8e23"), # Tissage
        (walid_id, "0869e1ee-6d4e-4fde-90e7-6fb139dffbda"), # Pierre
        (walid_id, "7322e61f-4aa5-4fd0-8545-c330bcfe3b60"), # Terre crue
        (walid_id, "e237f258-0226-4807-9924-2510f07b96dc"), # Zellige
    ]

    for user_id, form_id in client_enrollments:
        if not user_id or not form_id: continue
        enr_id = f"enr-{user_id[:6]}-{form_id[:6]}"
        db_exec(f"""
            INSERT IGNORE INTO formation_enrollments (id, formation_id, artisan_id, status, enrolled_at, created_at, updated_at)
            VALUES ('{enr_id}', '{form_id}', '{user_id}', 'ATTENDED', NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY, NOW());
        """)
        db_exec(f"UPDATE formation_enrollments SET status = 'ATTENDED' WHERE formation_id = '{form_id}' AND artisan_id = '{user_id}';")
    log("Completed masterclass enrollments seeded for client accounts.")

    # 3. Clean old dummy / test certifications before seeding real ones
    # Soft delete dummy ones or reset so we have clean authentic certifications
    for email in ARTISAN_CERTIFICATIONS.keys():
        uid = db_exec(f"SELECT id FROM users WHERE email = '{email}';").strip()
        if uid:
            # Delete old certifications with dummy titles
            db_exec(f"""
                DELETE FROM artisan_certifications 
                WHERE artisan_id = '{uid}' 
                  AND (title LIKE 'Woodcraft%' OR title LIKE 'Master Potter%' OR document_url LIKE '%.jpg' AND title LIKE '%Certificat d%');
            """)

    # 4. Generate and Upload authentic certifications for each user
    for email, cert_list in ARTISAN_CERTIFICATIONS.items():
        pwd = CLIENT_PASSWORD if email in ("sarah.cherif@souklab.dz", "walid.client@souklab.dz") else DEFAULT_PASSWORD
        token, uid = login(email, pwd)
        if not token:
            log(f"Could not login for {email}")
            continue

        log(f"Processing certifications for {email} ({uid})...")
        user_row = db_exec(f"SELECT first_name, last_name FROM users WHERE id = '{uid}';").split("\n")
        first = "Artisan"
        last = ""
        if len(user_row) > 0 and user_row[0]:
            parts = user_row[0].split("\t")
            first = parts[0]
            last = parts[1] if len(parts) > 1 else ""
        recipient_display = f"{first} {last}".strip()

        for cert in cert_list:
            # Check if this exact certificate title is already in DB
            title_esc = sql_escape(cert["title"])
            existing_count = db_scalar_int(f"SELECT count(*) FROM artisan_certifications WHERE artisan_id = '{uid}' AND title = '{title_esc}' AND deleted_at IS NULL;")
            if existing_count > 0:
                log(f"  Certification already exists: '{cert['title']}'")
                continue

            # Generate official PDF certificate
            pdf_path = generate_pdf_certificate(
                filename=cert["filename"],
                title=cert["title"],
                recipient=recipient_display,
                craft_or_description=cert["craft"],
                issuer=cert["issuer"],
                issued_at=cert["issuedAt"],
                credential_id=cert["credentialId"],
                is_attestation=cert.get("isAttestation", False)
            )

            # Upload via /api/v1/artisan/certifications
            cst, resp = upload_multipart(
                "/artisan/certifications",
                token,
                pdf_path,
                query_params={
                    "title": cert["title"],
                    "issuer": cert["issuer"],
                    "issuedAt": cert["issuedAt"]
                }
            )
            if cst in (200, 201):
                log(f"  Uploaded certification: '{cert['title']}' ({cst})")
            else:
                log(f"  Upload error for '{cert['title']}': status {cst}, resp: {resp}")

    # 5. Mark all official certifications as verified (is_verified = 1)
    db_exec("UPDATE artisan_certifications SET is_verified = 1 WHERE deleted_at IS NULL;")
    log("All official certifications updated to is_verified = 1.")

    # 6. Verify total counts in DB
    total_certs = db_exec("SELECT count(*) FROM artisan_certifications WHERE deleted_at IS NULL;")
    verified_certs = db_exec("SELECT count(*) FROM artisan_certifications WHERE deleted_at IS NULL AND is_verified = 1;")
    log(f"Database certifications count: {total_certs} total, {verified_certs} verified.")

    # 7. Add official Attestation course documents into formation_files for completed masterclasses
    # so participants can download them directly on /formations/[id]
    completed_formations = [
        ("f7501339-5279-4835-a0fd-eafba609b30d", "attest_sarah_poterie.pdf", "Attestation_Officielle_Formation_Poterie_Berbere.pdf"),
        ("0f8c74ee-1ec9-4e9c-924c-0e81605b70dd", "attest_sarah_broderie.pdf", "Attestation_Officielle_Broderie_Algeoise_Fetla.pdf"),
        ("73332d73-accb-433c-888f-7b6fd7be8e23", "cert_soraya_label.pdf", "Attestation_Formation_Tissage_Laine_Aures.pdf"),
        ("0869e1ee-6d4e-4fde-90e7-6fb139dffbda", "attest_walid_pierre.pdf", "Attestation_Stage_Taille_de_Pierre_Calcaire.pdf"),
        ("7322e61f-4aa5-4fd0-8545-c330bcfe3b60", "attest_walid_terre.pdf", "Attestation_Stage_Batisse_Terre_Crue_Pise.pdf"),
        ("e237f258-0226-4807-9924-2510f07b96dc", "cert_mohamed_bmz.pdf", "Attestation_Formation_Zellige_Mosaique_Art.pdf"),
    ]

    for form_id, local_pdf_name, orig_filename in completed_formations:
        local_path = os.path.join(CERT_DIR, local_pdf_name)
        if not os.path.exists(local_path): continue
        # Check if already added
        file_count = db_scalar_int(f"SELECT count(*) FROM formation_files WHERE formation_id = '{form_id}' AND original_filename = '{orig_filename}' AND deleted_at IS NULL;")
        if file_count > 0: continue

        # Upload file to MinIO container directly or via app
        file_size = os.path.getsize(local_path)
        storage_key = f"formation-{form_id[:8]}-{local_pdf_name}"
        
        # Copy to MinIO bucket souklab-files using minio mc pipe
        subprocess.run([
            "docker", "exec", "-i", "souklab_minio",
            "mc", "pipe", f"local/souklab-files/{storage_key}"
        ], input=open(local_path, "rb").read())

        # Insert formation_files record
        file_id = f"ff-{form_id[:8]}-{int(time.time())}"
        db_exec(f"""
            INSERT INTO formation_files (id, formation_id, original_filename, content_type, storage_key, file_size, created_at, updated_at)
            VALUES ('{file_id}', '{form_id}', '{orig_filename}', 'application/pdf', '{storage_key}', {file_size}, NOW(), NOW());
        """)
        log(f"Attached formation course document: {orig_filename} to formation {form_id[:8]}")

    log("Seeding certifications and attestations completed successfully!")

if __name__ == "__main__":
    main()
