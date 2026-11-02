#!/usr/bin/env python3
"""Package only the public user documentation shipped with an APK."""
import re
import json
import sys
import zipfile
from pathlib import Path

DOCUMENTS = (
    "README.md", "AI_DISCLOSURE.md", "CHANGELOG.md", "DISCLAIMER.md", "LICENSE",
    "NOTICE.md", "PRIVACY.md", "SECURITY.md", "SUPPORT.md", "THIRD_PARTY_NOTICES.md",
    "docs/README.md", "docs/INSTALL.md", "docs/USER_GUIDE.md", "docs/LEGAL.md",
    "docs/images/README.md", "docs/images/screenshots.json", "release-notes/next.md",
    "licenses/README.md", "licenses/dependency-inventory.json",
    "licenses/Apache-2.0.txt", "licenses/JUnit-EPL-1.0.txt", "licenses/MPL-2.0.txt",
    "licenses/OkHttp-PublicSuffix-NOTICE.txt", "licenses/Pikabu-Return-Minus-MIT.txt",
    "licenses/jsoup-MIT.txt", "licenses/publicsuffix-list-okhttp-4.12.0.txt", "licenses/runtime-notices.txt",
)

def package_docs(root: Path, destination: Path, version: str) -> Path:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Expected a plain release version")
    root = root.resolve()
    manifest = json.loads((root / "docs/images/screenshots.json").read_text())
    names = [entry["file"] for entry in manifest["images"]]
    if not names or len(set(names)) != len(names) or any(Path(name).name != name or not name.endswith(".png") for name in names):
        raise ValueError("User screenshots are missing")
    images = [root / "docs/images" / name for name in names]
    paths = [root / name for name in DOCUMENTS] + images
    for path in paths:
        if not path.is_file() or not path.resolve().is_relative_to(root) or any(p.is_symlink() for p in (path, *path.parents) if p != root.parent):
            raise ValueError(f"Missing or unsafe public document: {path.name}")
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / f"pikatv-v{version}-docs.zip"
    temporary = output.with_suffix(".zip.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(paths):
                info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return output

if __name__ == "__main__":
    print(package_docs(Path(__file__).resolve().parent.parent, Path(sys.argv[1]), sys.argv[2]).name)
