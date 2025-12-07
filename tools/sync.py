#!/usr/bin/env python3
import os
import sys
import json
import re
import shutil
import subprocess
from pathlib import Path

# Configuration
PROJECT_ROOT = Path(__file__).parent.parent
BRANDING_CONFIG = PROJECT_ROOT / "branding" / "branding.json"
UPSTREAM_URL = "https://gitlab.com/connect2x/tammy.git"
UPSTREAM_REMOTE = "upstream"
BRANCH = "main"

def run_command(args, ignore_errors=False, capture_output=False):
    """Runs a shell command."""
    try:
        if capture_output:
            result = subprocess.run(args, cwd=PROJECT_ROOT, check=True, capture_output=True, text=True)
            return result.stdout.strip()
        else:
            subprocess.run(args, cwd=PROJECT_ROOT, check=True)
    except subprocess.CalledProcessError as e:
        if not ignore_errors:
            print(f"Error running command: {' '.join(args)}")
            sys.exit(e.returncode)
        return None

def git_sync():
    """Syncs with upstream repository."""
    print("--- Syncing with Upstream ---")
    
    # Check if inside git repo
    if not (PROJECT_ROOT / ".git").exists():
        print("Error: Not a git repository.")
        sys.exit(1)

    # Add remote
    print(f"Checking remote '{UPSTREAM_REMOTE}'...")
    remotes = run_command(["git", "remote"], capture_output=True)
    if UPSTREAM_REMOTE not in remotes.splitlines():
        print(f"Adding remote '{UPSTREAM_REMOTE}' -> {UPSTREAM_URL}")
        run_command(["git", "remote", "add", UPSTREAM_REMOTE, UPSTREAM_URL])

    # Fetch
    print("Fetching upstream...")
    run_command(["git", "fetch", UPSTREAM_REMOTE, "--tags"])

    # Merge
    print(f"Merging {UPSTREAM_REMOTE}/{BRANCH} into {BRANCH}...")
    try:
        run_command(["git", "merge", f"{UPSTREAM_REMOTE}/{BRANCH}"])
    except SystemExit:
        print("Merge failed or conflicts detected. Please resolve conflicts manually.")
        # Proceed to branding anyway? Better to stop if merge completely failed.
        # But if conflicts exist, we might want to re-brand to fix some of them (e.g. settings.gradle).
        # We'll continue but warn.
        print("Warning: Proceeding with branding despite merge issues.")

def read_config():
    """Reads branding configuration."""
    if not BRANDING_CONFIG.exists():
        print(f"Error: Config file not found: {BRANDING_CONFIG}")
        sys.exit(1)
    
    with open(BRANDING_CONFIG, encoding='utf-8') as f:
        data = json.load(f)
    
    return data

def escape_kotlin_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')

def apply_branding(config):
    """Applies branding replacements and copies files."""
    print("\n--- Applying Branding ---")

    app_name = config.get('appName', 'TeleCrypt Messenger').strip()
    android_app_id = config.get('androidAppId', '').strip()
    ios_bundle_id = config.get('iosBundleId', '').strip() or android_app_id
    icon_dir_str = config.get('iconDir', '').strip()
    
    skip_android = not android_app_id
    android_dev_app_id = f"{android_app_id}.dev" if android_app_id else ""
    
    project_slug = app_name.lower()
    project_slug = re.sub(r'[^a-z0-9]+', '-', project_slug).strip('-') or 'telecrypt'

    # 1. Regex Replacements
    replacements = [
        (Path('build.gradle.kts'), r'val appName = "[^"]+"', f'val appName = "{escape_kotlin_string(app_name)}"'),
        (Path('settings.gradle.kts'), r'rootProject.name = "[^"]+"', f'rootProject.name = "{project_slug}"'),
        (Path('fastlane/Appfile'), r'app_identifier "[^"]+"', f'app_identifier "{ios_bundle_id}"'),
        (Path('iosApp/Configuration/Config.xcconfig'), r'PRODUCT_NAME=.*', f'PRODUCT_NAME={app_name}'),
        (Path('iosApp/Configuration/Config.xcconfig'), r'PRODUCT_BUNDLE_IDENTIFIER=.*', f'PRODUCT_BUNDLE_IDENTIFIER={ios_bundle_id}'),
        (Path('iosApp/iosApp/Info.plist'), r'<string>de.connect2x.tammy</string>', f'<string>{ios_bundle_id}</string>'),
         # Restore index.html branding (essential!)
        (Path('src/webMain/resources/index.html'), r'<title>Tammy</title>', f'<title>{app_name}</title>'),
        (Path('src/webMain/resources/index.html'), r'src="de.connect2x.tammy.js"', f'src="{android_app_id}.js"'),

    ]

    if not skip_android:
        replacements.extend([
            (Path('build.gradle.kts'), r'val appIdentifier = "[^"]+"', f'val appIdentifier = "{android_app_id}"'),
            (Path('fastlane/Appfile'), r'package_name "[^"]+"', f'package_name "{android_app_id}"'),
             # Update Android Manifest label via search/replace if string resource is used
            (Path('src/androidMain/AndroidManifest.xml'), r'de.connect2x.tammy', android_app_id),
        ])

    for rel_path, pattern, replacement in replacements:
        path = PROJECT_ROOT / rel_path
        if not path.exists():
            continue
        original_text = path.read_text(encoding='utf-8')
        new_text = re.sub(pattern, replacement, original_text)
        if new_text != original_text:
            print(f"Updated {rel_path}")
            path.write_text(new_text, encoding='utf-8')

    # 2. Template Replacements (Flatpak)
    for tmpl in ['flatpak/metainfo.xml.tmpl', 'flatpak/manifest.json.tmpl', 'flatpak/app.desktop.tmpl']:
        path = PROJECT_ROOT / tmpl
        if path.exists():
            text = path.read_text(encoding='utf-8')
            new_text = text.replace('Tammy', app_name)
            if not skip_android:
                new_text = new_text.replace('de.connect2x.tammy', android_app_id)
            if new_text != text:
                print(f"Updated {tmpl}")
                path.write_text(new_text, encoding='utf-8')

    # 3. Google Services JSON
    if not skip_android:
        gs_path = PROJECT_ROOT / 'google-services.json'
        if gs_path.exists():
            text = gs_path.read_text(encoding='utf-8')
            updated = text.replace('"package_name": "de.connect2x.tammy.dev"', f'"package_name": "{android_dev_app_id}"') \
                          .replace('"package_name": "de.connect2x.tammy"', f'"package_name": "{android_app_id}"')
            if updated != text:
                print("Updated google-services.json")
                gs_path.write_text(updated, encoding='utf-8')

    # 4. Copy Icons
    icon_dir = PROJECT_ROOT / icon_dir_str
    if not icon_dir.exists():
        print(f"Warning: Icon directory not found at {icon_dir}")
    else:
        def copy_dir(src_name, dest_rel):
            src = icon_dir / src_name
            dest = PROJECT_ROOT / dest_rel
            if src.exists():
                if dest.exists():
                    shutil.rmtree(dest) # Remove existing to be clean
                shutil.copytree(src, dest, dirs_exist_ok=True)
                print(f"Copied icons: {src_name} -> {dest_rel}")
            else:
                print(f"Warning: Icon source not found: {src_name}")

        if not skip_android:
             # Android requires special handling for ic_launcher cleanup
             android_res = PROJECT_ROOT / "src/androidMain/res"
             if android_res.exists():
                for f in android_res.glob("**/ic_launcher*.png"): f.unlink()
                for f in android_res.glob("**/ic_launcher*.webp"): f.unlink()
             copy_dir("android", "src/androidMain/res")
        
        copy_dir("ios/AppIcon.appiconset", "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
        copy_dir("desktop", "src/desktopMain/resources")
        copy_dir("desktop-msix", "build/compose/binaries/main-release/msix")

def main():
    git_sync()
    
    config = read_config()
    apply_branding(config)
    
    print("\n--- Sync & Branding Complete ---")
    run_command(["git", "status", "-sb"])

    # Attempt to fix the build? No, that's too much magic.

if __name__ == "__main__":
    main()
