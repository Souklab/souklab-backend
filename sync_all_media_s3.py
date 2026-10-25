#!/usr/bin/env python3
"""
Sync all media assets (avatars, galleries, certifications, formation thumbnails, post media)
into MinIO S3 storage and ensure full database referential consistency.
"""

import json
import mimetypes
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

API_BASE = "http://localhost:8080/api/v1"
ADMIN_EMAIL = "admin@souklab.dz"
ADMIN_PASSWORD = "Souklab-Admin-2026!"
ARTISAN_PASSWORD = "Demo-artisan-2026!"
CLIENT_PASSWORD = "Demo-client-2026!"
IMAGES_DIR = "/tmp/souklab_seed_images"

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

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
        with urllib.request.urlopen(req, timeout=25) as resp:
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

def login(email, password):
    status, res = api_call("POST", "/auth/login", json_body={"email": email, "password": password})
    if status == 200 and res and res.get("data"):
        return res["data"]["accessToken"], res["data"]["user"]
    raise Exception(f"Login failed for {email}: {status} {res}")

def main():
    log("=== STARTING COMPLETE S3 MEDIA SYNC ===")
    
    # 1. Admin login
    admin_token, _ = login(ADMIN_EMAIL, ADMIN_PASSWORD)
    log("Admin authenticated successfully.")

    # Import data structures from seed script
    current_dir = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, current_dir)
    from seed_artisanal_data import ARTISANS_DATA, FORMATIONS_DATA, FEED_POSTS, CLIENTS_DATA

    tokens = {}

    # 2. Sync Artisans (Avatars, Galleries, Certifications)
    log("--- Synchronizing Artisans ---")
    # Clean previous broken gallery and certifications
    run_db_query("DELETE FROM artisan_gallery_images WHERE artisan_id IN (SELECT id FROM users WHERE email LIKE '%@souklab.dz');")
    run_db_query("DELETE FROM artisan_certifications WHERE artisan_id IN (SELECT id FROM users WHERE email LIKE '%@souklab.dz');")
    log("Cleaned stale gallery and certification records.")

    for art in ARTISANS_DATA:
        email = art["email"]
        try:
            tok, udata = login(email, ARTISAN_PASSWORD)
            tokens[email] = tok
            uid = udata["id"]
        except Exception as e:
            log(f"Could not login as {email}: {e}")
            continue

        # Upload Avatar
        av_path = os.path.join(IMAGES_DIR, art["avatar"])
        if os.path.exists(av_path):
            st, _ = upload_multipart("/users/me/avatars", tok, av_path)
            log(f"Avatar synced for {art['firstName']} {art['lastName']}: status {st}")

        # Upload Gallery Images
        for gfile, gtitle, gcap in art["gallery"]:
            gpath = os.path.join(IMAGES_DIR, gfile)
            if os.path.exists(gpath):
                st, _ = upload_multipart("/artisan/gallery", tok, gpath, query_params={"title": gtitle, "caption": gcap})
                log(f"  Gallery image '{gtitle}' uploaded: {st}")

        # Upload Certification
        cert = art["certification"]
        cpath = os.path.join(IMAGES_DIR, cert["file"])
        if os.path.exists(cpath):
            st, _ = upload_multipart(
                "/artisan/certifications",
                tok,
                cpath,
                query_params={"title": cert["title"], "issuer": cert["issuer"], "issuedAt": cert["issuedAt"]}
            )
            log(f"  Certification '{cert['title']}' uploaded: {st}")

    # 3. Demo Artisans Avatars
    log("--- Synchronizing Demo Artisans Avatars ---")
    for demo_email, demo_avatar in [
        ("karim.haddad@demo.souklab.test", "pottery_2.jpg"),
        ("nadia.benali@demo.souklab.test", "clothing_2.jpg"),
        ("yacine.mansouri@demo.souklab.test", "earth_2.jpg"),
        ("sophie.martin@demo.souklab.test", "stone_2.jpg"),
        ("amina.kaci@demo.souklab.test", "zellige_2.jpg"),
    ]:
        try:
            dtok, _ = login(demo_email, ARTISAN_PASSWORD)
            av_path = os.path.join(IMAGES_DIR, demo_avatar)
            if os.path.exists(av_path):
                st, _ = upload_multipart("/users/me/avatars", dtok, av_path)
                log(f"Avatar synced for demo artisan {demo_email}: status {st}")
        except Exception as e:
            log(f"Notice demo artisan avatar {demo_email}: {e}")

    # 4. Formations Thumbnails
    log("--- Synchronizing Formations Thumbnails ---")
    for form in FORMATIONS_DATA:
        f_email = form["formateurEmail"]
        f_tok = tokens.get(f_email)
        if not f_tok:
            try:
                f_tok, _ = login(f_email, ARTISAN_PASSWORD)
                tokens[f_email] = f_tok
            except Exception:
                continue

        form_title = form["body"]["title"]
        safe_title = form_title.replace("'", "''")
        fid = db_scalar(f"SELECT id FROM formations WHERE title = '{safe_title}';")
        if fid:
            t_file = os.path.join(IMAGES_DIR, form["thumbnail"])
            if os.path.exists(t_file):
                st, _ = upload_multipart(f"/artisan/formations/{fid}/thumbnail", f_tok, t_file)
                log(f"Thumbnail uploaded for formation '{form_title}': status {st}")

    # 5. Feed Posts Media
    log("--- Synchronizing Feed Posts Media ---")
    run_db_query("DELETE FROM feed_post_media WHERE post_id IN (SELECT id FROM feed_posts WHERE author_id IN (SELECT id FROM users WHERE email LIKE '%@souklab.dz'));")
    log("Cleaned stale feed post media.")

    for post in FEED_POSTS:
        auth_email = post["authorEmail"]
        a_tok = tokens.get(auth_email)
        if not a_tok:
            try:
                a_tok, _ = login(auth_email, ARTISAN_PASSWORD)
                tokens[auth_email] = a_tok
            except Exception:
                continue

        post_title = post["title"]
        safe_title = post_title.replace("'", "''")
        pid = db_scalar(f"SELECT id FROM feed_posts WHERE title = '{safe_title}';")
        if pid:
            for pphoto in post["photos"]:
                ppath = os.path.join(IMAGES_DIR, pphoto)
                if os.path.exists(ppath):
                    st, _ = upload_multipart(f"/feed/{pid}/media", a_tok, ppath)
                    log(f"Media '{pphoto}' uploaded to post '{post_title}': status {st}")
            # Admin approves/publishes post after media addition
            api_call("POST", f"/admin/feed/{pid}/publish", token=admin_token, json_body={"note": "Validation finale des médias."})

    # 6. Clients Avatars
    log("--- Synchronizing Clients Avatars ---")
    for cl in CLIENTS_DATA:
        cemail = cl["email"]
        try:
            cl_tok, _ = login(cemail, CLIENT_PASSWORD)
            c_av = os.path.join(IMAGES_DIR, cl["avatar"])
            if os.path.exists(c_av):
                st, _ = upload_multipart("/users/me/avatars", cl_tok, c_av)
                log(f"Avatar synced for client {cemail}: status {st}")
        except Exception as e:
            log(f"Client avatar notice {cemail}: {e}")

    log("=== S3 MEDIA SYNC COMPLETED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
