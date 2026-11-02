import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("package_docs", Path(__file__).with_name("package-docs.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PublicDocumentationPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.out = Path(self.temp.name) / "output"
        for name in module.DOCUMENTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("Public document\n")
        (self.root / "docs/images/screenshots.json").write_text(json.dumps({"images": [{"file": "public.png"}]}))
        (self.root / "docs/images/public.png").write_bytes(b"public picture")

    def test_private_notes_and_unlisted_screenshots_never_enter_release(self):
        for name in ("AGENTS.md", "docs/INTERNAL.md", "docs/images/private.png", "licenses/private.p12"):
            (self.root / name).write_text("PRIVATE MATERIAL")
        package = module.package_docs(self.root, self.out, "1.2.3")
        with zipfile.ZipFile(package) as archive:
            self.assertIn("LICENSE", archive.namelist())
            self.assertIn("docs/images/public.png", archive.namelist())
            self.assertFalse(any(b"PRIVATE MATERIAL" in archive.read(name) for name in archive.namelist()))

    def test_linked_document_cannot_read_outside_the_public_source(self):
        secret = Path(self.temp.name) / "private.txt"
        secret.write_text("PRIVATE MATERIAL")
        path = self.root / "NOTICE.md"
        path.unlink()
        path.symlink_to(secret)
        with self.assertRaises(ValueError):
            module.package_docs(self.root, self.out, "1.2.3")
        self.assertFalse(self.out.exists())

    def test_screenshot_manifest_rejects_parent_path(self):
        (self.root / "docs/images/screenshots.json").write_text(json.dumps({"images": [{"file": "../../private.png"}]}))
        with self.assertRaises(ValueError):
            module.package_docs(self.root, self.out, "1.2.3")

if __name__ == "__main__":
    unittest.main()
