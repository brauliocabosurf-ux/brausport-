#!/usr/bin/env python3
"""Read the packaged NSC through its manifest reference, even with shortened paths."""
import re
import subprocess
import sys


def resolve_path(manifest: str, resources: str) -> str:
    refs = re.findall(
        r"A: (?:http://schemas\.android\.com/apk/res/android:|android:)"
        r"networkSecurityConfig(?:\(0x[0-9a-fA-F]+\))?=@(0x[0-9a-fA-F]+)\b",
        manifest,
    )
    if len(refs) != 1:
        raise ValueError("El manifiesto debe tener una referencia NSC inequívoca")
    blocks = re.split(r"(?m)^\s*resource ", resources)[1:]
    matches = [b for b in blocks if b.split()[0].lower() == refs[0].lower()]
    if len(matches) != 1:
        raise ValueError("No se encontró el recurso NSC del manifiesto")
    block = matches[0]
    if not block.split()[1].startswith("xml/"):
        raise ValueError("El NSC no es un recurso XML")
    values = re.findall(r"(?m)^\s*\(([^)]*)\)\s+(.+)$", block)
    if len(values) != 1 or values[0][0] != "":
        raise ValueError("Se requiere un único NSC sin variantes de configuración")
    path = re.fullmatch(r"\(file\) (res/\S+) type=XML(?: src=.*)?", values[0][1])
    if path is None:
        raise ValueError("El NSC no apunta a un archivo XML empaquetado")
    return path[1]


def main() -> None:
    aapt2, apk = sys.argv[1:]

    def dump(*args: str) -> str:
        return subprocess.check_output([aapt2, "dump", *args], text=True)

    path = resolve_path(
        dump("xmltree", apk, "--file", "AndroidManifest.xml"),
        dump("resources", apk),
    )
    print(f"NSC empaquetado: {path}", file=sys.stderr)
    print(dump("xmltree", apk, "--file", path), end="")


if __name__ == "__main__":
    main()
