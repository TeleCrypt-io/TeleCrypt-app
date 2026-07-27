#!/usr/bin/env python3
"""Apply TeleCrypt / upstream branding from a config JSON.

Invoked by tools/post_merge.sh (TeleCrypt branding) and tools/pre_merge.sh
(upstream Tammy branding). The script is idempotent: running it twice with the
same config produces no further changes.

Replacement rules are data-driven from the config JSON. A small number of
"swap" rules (deep-link scheme, app-name prose, footer links) need to know the
*other* side's value; those are handled with alternation regexes or by
branching on the current mode.
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def slugify(app_name: str) -> str:
    s = app_name.strip().lower()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s or "telecrypt"


def replace_in_file(path: Path, pattern: str, replacement: str, *, flags=0, all_matches=True):
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    new_text = re.sub(pattern, replacement, text, flags=flags)
    if new_text != text:
        path.write_text(new_text, encoding="utf-8")


def apply(config: dict, mode: str):
    app_name = config["appName"]
    android_app_id = config["androidAppId"]
    ios_app_id = config.get("iosBundleId", android_app_id)
    base_name = config.get("baseName", "TeleCryptUI")
    homepage = config.get("homepage", "https://telecrypt.io")
    website_base_url = config.get("websiteBaseUrl", homepage)
    send_logs = config.get("sendLogsEmailAddress", None)
    push_url = config.get("pushUrl", "")
    deep_link_scheme = config.get("deepLinkScheme", "telecrypt")
    company = config.get("company", "TeleCrypt.IO")
    source_url = config.get("sourceUrl", "https://github.com/TeleCrypt-io/TeleCrypt-app")
    web_app_url = config.get("webAppUrl", "https://app.telecrypt.io")
    ios_scheme = config.get("iosScheme", f"{app_name} for iOS")
    project_slug = config.get("projectSlug") or slugify(app_name)

    send_logs_lit = "null" if send_logs is None else f'"{send_logs}"'
    push_url_lit = "null" if push_url is None else f'"{push_url}"'

    is_post = mode == "post"
    brand_prefix = "TeleCrypt" if is_post else "Tammy"

    # 1. build.gradle.kts
    bg = REPO / "build.gradle.kts"
    replace_in_file(bg, r'val appName = "[^"]*"', f'val appName = "{app_name}"')
    replace_in_file(bg, r'val appIdentifier = "[^"]*"', f'val appIdentifier = "{android_app_id}"')
    replace_in_file(bg, r'val appId = "[^"]*"', f'val appId = "{android_app_id}"')
    replace_in_file(bg, r'val appHomepage = "[^"]*"', f'val appHomepage = "{homepage}"')
    replace_in_file(bg, r'baseName = "[^"]*"', f'baseName = "{base_name}"')
    replace_in_file(bg, r'homepage = "[^"]*"', f'homepage = "{homepage}"')
    replace_in_file(bg, r'val websiteBaseUrl = "[^"]*"', f'val websiteBaseUrl = "{website_base_url}"')

    # 2. settings.gradle.kts
    replace_in_file(REPO / "settings.gradle.kts", r'rootProject.name = "[^"]*"', f'rootProject.name = "{project_slug}"')

    # 3. fastlane/Appfile
    af = REPO / "fastlane/Appfile"
    replace_in_file(af, r'app_identifier "[^"]*"', f'app_identifier "{ios_app_id}"')
    replace_in_file(af, r'package_name "[^"]*"', f'package_name "{android_app_id}"')

    # 4. fastlane/Fastfile
    ff = REPO / "fastlane/Fastfile"
    replace_in_file(ff, r'scheme: "[^"]*"', f'scheme: "{ios_scheme}"')
    replace_in_file(ff, r'package_name: "[^"]*"', f'package_name: "{android_app_id}"')

    # 5. iOS Config.xcconfig
    xc = REPO / "iosApp/Configuration/Config.xcconfig"
    replace_in_file(xc, r'PRODUCT_NAME=.*', f'PRODUCT_NAME={app_name}')
    replace_in_file(xc, r'PRODUCT_BUNDLE_IDENTIFIER=.*', f'PRODUCT_BUNDLE_IDENTIFIER={ios_app_id}')

    # 6. iOS Info.plist (URL name + scheme)
    plist = REPO / "iosApp/iosApp/Info.plist"
    if plist.exists():
        text = plist.read_text(encoding="utf-8")
        text = re.sub(
            r'(<key>CFBundleURLName</key>\s*<string>)[^<]*(</string>)',
            lambda m: f"{m.group(1)}{ios_app_id}{m.group(2)}",
            text,
        )
        text = re.sub(
            r'(<key>CFBundleURLSchemes</key>\s*<array>\s*<string>)[^<]*(</string>)',
            lambda m: f"{m.group(1)}{deep_link_scheme}{m.group(2)}",
            text,
        )
        plist.write_text(text, encoding="utf-8")

    # 8. Deep-link scheme in source (idempotent via alternation)
    scheme_re = r'(?:de\.connect2x\.tammy|telecrypt)://'
    for src in [
        REPO / "src/commonMain/kotlin/de/connect2x/tammy/telecryptModules/call/CallDeepLink.kt",
        REPO / "src/commonTest/kotlin/de/connect2x/tammy/telecryptModules/call/CallDeepLinkTest.kt",
        REPO / "src/jvmMain/kotlin/de/connect2x/tammy/Main.kt",
        REPO / "src/jvmMain/kotlin/de/connect2x/tammy/SsoCallbackServer.kt",
    ]:
        replace_in_file(src, scheme_re, f"{deep_link_scheme}://")

    # 9. Web index.html (JS bundle filename tied to appIdentifier)
    index_html = REPO / "src/webMain/resources/index.html"
    replace_in_file(index_html, r'src="[^"]*\.js"', f'src="{android_app_id}.js"')

    # 10. tammyConfiguration.kt
    tc = REPO / "src/commonMain/kotlin/de/connect2x/tammy/tammyConfiguration.kt"
    replace_in_file(tc, r'sendLogsEmailAddress = [^\n]*', f'sendLogsEmailAddress = {send_logs_lit}')
    replace_in_file(tc, r'pushUrl = [^\n]*', f'pushUrl = {push_url_lit}')

    # 11. website/hugo.yaml
    replace_in_file(REPO / "website/hugo.yaml", r'baseURL: .*', f'baseURL: {homepage}')

    # 12. website i18n
    for lang, dl_title in [
        ("en-US", config.get("downloadTitleEn", f"Download {app_name}")),
        ("de-DE", config.get("downloadTitleDe", f"{app_name} herunterladen")),
    ]:
        i18n = REPO / f"website/i18n/{lang}.yaml"
        replace_in_file(i18n, r'^title: .*', f'title: {app_name}', flags=re.MULTILINE)
        replace_in_file(i18n, r'^company: .*', f'company: {company}', flags=re.MULTILINE)
        replace_in_file(i18n, r'^  title: (Download|Lade) .*', f'  title: {dl_title}', flags=re.MULTILINE)

    # 13. website layouts (downloads keys, appinstaller, play store id, web app url)
    for layout in [
        REPO / "website/layouts/index.html",
        REPO / "website/layouts/_default/single.html",
    ]:
        if not layout.exists():
            continue
        replace_in_file(layout, r'downloads\.(Tammy|TeleCrypt)', f'downloads.{brand_prefix}')
        replace_in_file(layout, r'(Tammy|TeleCrypt)-Windows-(x64|arm64)\.appinstaller',
                        f'{brand_prefix}-Windows-\\2.appinstaller')
        replace_in_file(layout, r'id=(de\.connect2x\.tammy|io\.telecrypt\.app)',
                        f'id={android_app_id}')
        replace_in_file(layout,
                        r'(https://app\.tammy\.connect2x\.de|https://app\.telecrypt\.io)',
                        web_app_url)

    # 14. website footer links (matrix room / source code)
    post_matrix = "<li><a href='https://github.com/TeleCrypt-io/TeleCrypt-app/discussions'>TeleCrypt Discussions</a></li>"
    pre_matrix = "<li><a href='matrix:r/tammy:imbitbu.de'>#tammy:imbitbu.de</a></li>"
    post_source = "<li><a href='https://github.com/TeleCrypt-io/TeleCrypt-app'>{{ i18n \"sourcecode\" }}</a></li>"
    pre_source = "<li><a href='https://gitlab.com/connect2x/tammy'>{{ i18n \"sourcecode\" }}</a></li>"
    for layout in [
        REPO / "website/layouts/index.html",
        REPO / "website/layouts/_default/single.html",
    ]:
        if not layout.exists():
            continue
        if is_post:
            replace_in_file(layout, re.escape(pre_matrix), post_matrix)
            replace_in_file(layout, re.escape(pre_source), post_source)
        else:
            replace_in_file(layout, re.escape(post_matrix), pre_matrix)
            replace_in_file(layout, re.escape(post_source), pre_source)

    # 15. website/.gitignore appinstaller names
    wgi = REPO / "website/.gitignore"
    replace_in_file(wgi, r'static/(Tammy|TeleCrypt)-Windows-(x64|arm64)\.appinstaller',
                    f'static/{brand_prefix}-Windows-\\2.appinstaller')

    # 16. fastlane metadata prose (app name swap)
    for f in [
        REPO / "fastlane/metadata/android/en-US/title.txt",
        REPO / "fastlane/metadata/android/en-US/short_description.txt",
        REPO / "fastlane/metadata/android/en-US/full_description.txt",
        REPO / "fastlane/metadata/android/en-US/changelogs/default.txt",
        REPO / "fastlane/metadata/android/de-DE/title.txt",
        REPO / "fastlane/metadata/android/de-DE/short_description.txt",
        REPO / "fastlane/metadata/android/de-DE/full_description.txt",
        REPO / "fastlane/metadata/android/de-DE/changelogs/default.txt",
    ]:
        if not f.exists():
            continue
        if is_post:
            replace_in_file(f, "Tammy", app_name)
        else:
            replace_in_file(f, "TeleCrypt Messenger", "Tammy")

    # 17. legal pages (company + email)
    legal_files = [
        REPO / "website/content/privacy.en-US.md",
        REPO / "website/content/privacy.de-DE.md",
        REPO / "website/content/imprint.en-US.md",
        REPO / "website/content/imprint.de-DE.md",
    ]
    for f in legal_files:
        if not f.exists():
            continue
        if is_post:
            replace_in_file(f, "connect2x GmbH", company)
            replace_in_file(f, "contact@connect2x.de", "support@telecrypt.io")
            replace_in_file(f, "kontakt@connect2x.de", "support@telecrypt.io")
        else:
            replace_in_file(f, "TeleCrypt.IO", "connect2x GmbH")
            if "de-DE" in str(f):
                replace_in_file(f, "support@telecrypt.io", "kontakt@connect2x.de")
            else:
                replace_in_file(f, "support@telecrypt.io", "contact@connect2x.de")


def main():
    if len(sys.argv) != 3:
        print("usage: apply_branding.py <post|pre> <config.json>", file=sys.stderr)
        sys.exit(1)
    mode = sys.argv[1]
    config_path = Path(sys.argv[2])
    if mode not in ("post", "pre"):
        print(f"unknown mode: {mode}", file=sys.stderr)
        sys.exit(1)
    config = load_config(config_path)
    apply(config, mode)
    print(f"[apply_branding] applied {mode} branding from {config_path}")


if __name__ == "__main__":
    main()
