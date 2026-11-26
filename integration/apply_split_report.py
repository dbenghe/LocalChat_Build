"""Install the compact status reports in the owned and standalone engines."""
import ast
import shutil
from pathlib import Path


def apply(package):
    package = Path(package)
    target = package / "mbs_rapport.py"
    helper = Path(__file__).with_name("split_report.py")
    if not target.is_file():
        return
    source = target.read_bytes()
    anchor = b"def genereer(uit_pad=None, tekeningen_bijvoegen=True, alleen_problemen=True,"
    if b"from mbs_split_report import generate" not in source:
        if source.count(anchor) != 1:
            raise ValueError(f"Unsupported report entry point: {target}")
        delimiter = b"\r\n" if b"\r\n" in source else b"\n"
        wrapper = (
            'def genereer(uit_pad=None, tekeningen_bijvoegen=False, alleen_problemen=True,\n'
            '             voortgang=None):\n'
            '    import sys\n'
            '    from mbs_split_report import generate\n'
            '    info = generate(sys.modules[__name__], laad(), uit_pad, tekeningen_bijvoegen, voortgang)\n'
            '    info["scope"] = "problems" if alleen_problemen else "all"\n'
            '    return info\n\n\n'
        ).encode("utf-8").replace(b"\n", delimiter)
        source = source.replace(anchor, wrapper + anchor.replace(b"genereer(", b"genereer_uitgebreid("))
    ast.parse(source)
    shutil.copy2(helper, package / "mbs_split_report.py")
    if target.read_bytes() != source:
        target.write_bytes(source)
