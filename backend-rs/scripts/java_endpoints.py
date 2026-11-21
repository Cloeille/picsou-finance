"""Lists every HTTP endpoint the Java backend declares, as TSV: METHOD, PATH, CONTROLLER, JAVA_METHOD."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "backend/src/main/java/com/picsou"
MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\s*(\((?:[^()]|\([^()]*\))*\))?", re.S)
VERBS = {"Get": "GET", "Post": "POST", "Put": "PUT", "Patch": "PATCH", "Delete": "DELETE"}


def paths(args):
    if not args:
        return [""]
    found = re.findall(r'"([^"]*)"', re.sub(r"(produces|consumes|params|headers)\s*=\s*(\{[^}]*\}|\"[^\"]*\"|[\w.]+)", "", args))
    return found or [""]


def methods(kind, args):
    if kind != "Request":
        return [VERBS[kind]]
    found = re.findall(r"RequestMethod\.(\w+)", args or "")
    return found or ["ANY"]


rows = []
for source in sorted(ROOT.rglob("*.java")):
    text = source.read_text()
    if "@RestController" not in text and "@Controller" not in text:
        continue
    head = re.split(r"^(?:public\s+|final\s+|abstract\s+)*class\s", text, maxsplit=1, flags=re.M)
    prefixes = [""]
    class_part = head[0]
    for m in MAPPING.finditer(class_part):
        if m.group(1) == "Request":
            prefixes = paths(m.group(2))
    body = head[1] if len(head) > 1 else ""
    for m in MAPPING.finditer(body):
        tail = body[m.end():]
        name = re.search(r"\b(\w+)\s*\(", re.sub(r"@\w+(\((?:[^()]|\([^()]*\))*\))?", "", tail[:600]))
        for prefix in prefixes:
            for p in paths(m.group(2)):
                for verb in methods(m.group(1), m.group(2)):
                    rows.append((verb, (prefix + p) or "/", source.stem, name.group(1) if name else "?"))

for row in sorted(set(rows), key=lambda r: (r[1], r[0])):
    print("\t".join(row))
print(f"{len(set(rows))} endpoints", file=sys.stderr)
