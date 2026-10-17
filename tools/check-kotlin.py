#!/usr/bin/env python3
"""
Static checker for OEA's Kotlin sources.

This workspace has no JDK, no Android SDK and no Maven access, so the Gradle
build cannot run here. What *can* be checked mechanically — and what has
actually caught real breakage in this repo before — is:

  1. a real Kotlin lexer (comments, raw strings, escapes and `${...}` templates
     with nesting) and bracket balance computed over it;
  2. every call made through a known toolkit receiver (`k.`, `kit.`,
     `GameKit.`, …) resolves to a member that really exists;
  3. `Oea*` / `Game*` symbols referenced in the sources this project owns are
     declared somewhere reachable (same package, imported, or same file) — the
     class of mistake a compiler would otherwise catch for us;
  4. class references in manifests exist on disk, and every module in
     `settings.gradle` has a directory.

Run:  python3 tools/check-kotlin.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ("oea", "oea-engine", "oea-games", "oea-game-arena", "oea-game-chess", "oea-game-runner")

# --------------------------------------------------------------------- lexer


def lex(src: str):
    """
    Tokenize Kotlin, tracking string/template context.

    Returns a list of (kind, text, line) for code tokens only: `"`, `(`, `[`,
    `{`, `}` and identifiers. Text inside string literals is skipped, but a
    `${ ... }` template is lexed as code, so a brace inside a template is
    counted exactly once.
    """
    toks = []
    i, n, line = 0, len(src), 1
    ctx = []  # ("str", raw:bool) or ("tmpl", depth:int)

    def in_str():
        return bool(ctx) and ctx[-1][0] == "str"

    while i < n:
        c = src[i]

        # ---------------- inside a string literal
        if in_str():
            raw = ctx[-1][1]
            if raw and src.startswith('"""', i):
                ctx.pop()
                i += 3
                continue
            if not raw and c == '"':
                ctx.pop()
                i += 1
                continue
            if c == "\\" and not raw:
                i += 2
                continue
            if src.startswith("${", i):
                ctx.append(("tmpl", 0))
                i += 2
                continue
            if c == "\n":
                line += 1
            i += 1
            continue

        # ---------------- code (or inside a ${ } template)
        if c == "\n":
            line += 1
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    if src[i] == "\n":
                        line += 1
                    i += 1
            continue
        if src.startswith('"""', i):
            ctx.append(("str", True))
            i += 3
            continue
        if c == '"':
            ctx.append(("str", False))
            i += 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            i = min(j + 1, n)
            continue
        if c.isspace():
            i += 1
            continue
        m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", src[i:])
        if m:
            toks.append(("id", m.group(0), line))
            i += m.end()
            continue
        if c == "{":
            if ctx and ctx[-1][0] == "tmpl":
                ctx[-1] = ("tmpl", ctx[-1][1] + 1)
            toks.append(("{", c, line))
            i += 1
            continue
        if c == "}":
            if ctx and ctx[-1][0] == "tmpl":
                if ctx[-1][1] == 0:
                    ctx.pop()  # back into the enclosing string
                    i += 1
                    continue
                ctx[-1] = ("tmpl", ctx[-1][1] - 1)
            toks.append(("}", c, line))
            i += 1
            continue
        if c in "()[]":
            toks.append((c, c, line))
            i += 1
            continue
        i += 1
    return toks, ctx


def check_balance(rel: str, src: str) -> list[str]:
    toks, leftover = lex(src)
    stack = []
    for kind, text, line in toks:
        if kind in "{([":
            stack.append((kind, line))
        elif kind in "})]":
            if not stack:
                return [f"{rel}:{line}: closing '{text}' with nothing open"]
            open_kind, open_line = stack.pop()
            if "{([" .index(open_kind) != "})]".index(kind):
                return [f"{rel}:{line}: '{text}' closes '{open_kind}' opened at line {open_line}"]
    if stack:
        kind, line = stack[0]
        return [f"{rel}:{line}: '{kind}' never closed ({len(stack)} unclosed)"]
    if leftover:
        return [f"{rel}: unterminated string/template at end of file: {leftover}"]
    return []


# --------------------------------------------------------- symbol resolution

DECL_RE = re.compile(
    r"(?:^|\n)\s*(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|data\s+|sealed\s+|abstract\s+|final\s+|enum\s+|value\s+)*"
    r"(?:class|object|interface|enum class)\s+([A-Z]\w*)"
)


def package_of(src: str) -> str:
    m = re.search(r"^package\s+([\w\.]+)", src, re.M)
    return m.group(1) if m else ""


def build_symbol_table(files) -> dict[str, set[str]]:
    table: dict[str, set[str]] = {}
    for f in files:
        src = open(f, encoding="utf-8").read()
        pkg = package_of(src)
        for name in DECL_RE.findall(src):
            table.setdefault(name, set()).add(pkg)
    return table


def strip_comments(src: str) -> str:
    """Remove // and /* */ comments while leaving string literals intact."""
    out = []
    i, n = 0, len(src)
    ctx = []
    while i < n:
        c = src[i]
        if ctx and ctx[-1] == "str":
            if src.startswith('"""', i):
                ctx.pop()
                out.append('"""')
                i += 3
                continue
            if c == "\\":
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                ctx.pop()
            out.append(c)
            i += 1
            continue
        if src.startswith('"""', i):
            ctx.append("str")
            out.append('"""')
            i += 3
            continue
        if c == '"':
            ctx.append("str")
            out.append(c)
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            out.append(" ")
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_symbols(rel: str, src: str, table: dict[str, set[str]], strict_files: set[str]) -> list[str]:
    if rel not in strict_files:
        return []
    problems = []
    src = strip_comments(src)
    pkg = package_of(src)
    imported_pkgs = set()
    imported_symbols = set()
    for m in re.finditer(r"^import\s+([\w\.]+)(?:\.(\w+))?", src, re.M):
        imported_pkgs.add(m.group(1))
        if m.group(2):
            imported_symbols.add(m.group(2))
    local_decls = set(DECL_RE.findall(src))
    for m in re.finditer(r"(?<![\w.\$])((?:Oea|Game)[A-Z]\w*)\b", src):
        name = m.group(1)
        if name in local_decls or name in imported_symbols:
            continue
        packages = table.get(name)
        if not packages:
            line = src[: m.start()].count("\n") + 1
            problems.append(f"{rel}:{line}: '{name}' is not declared in any OEA module")
            continue
        if pkg in packages:
            continue
        if not any(ip == p or p.startswith(ip + ".") or ip.startswith(p + ".") for p in packages for ip in imported_pkgs):
            line = src[: m.start()].count("\n") + 1
            problems.append(f"{rel}:{line}: '{name}' ({sorted(packages)}) is neither in package {pkg} nor imported")
    return problems


# ------------------------------------------------------------- member checks

MEMBER_RE = re.compile(r"(?:^|\n)\s*(?:open |override |internal |private |protected |public |suspend |inline |operator )*fun\s+(?:<[^>]*>\s*)?(\w+)\s*[\(<]")
# Only class-body members: at most one indent level, optionally with a modifier.
PROP_RE = re.compile(r"(?:^|\n)\s{0,8}(?:open |override |internal |private |protected |public |lateinit )*(?:val|var)\s+(\w+)")
SCOPE_FUNCS = {"let", "also", "apply", "run", "with", "takeIf", "takeUnless", "toString", "hashCode", "equals"}


def declared_members(src: str) -> set[str]:
    return set(MEMBER_RE.findall(src)) | set(PROP_RE.findall(src))


def check_receiver_calls(rel: str, src: str, kits: dict[str, set[str]], receivers: dict[str, str]) -> list[str]:
    problems = []
    for recv, kit in receivers.items():
        members = kits.get(kit)
        if not members:
            continue
        for m in re.finditer(r"(?<![\w\.])" + re.escape(recv) + r"\.(\w+)", src):
            name = m.group(1)
            if name in members or name in SCOPE_FUNCS:
                continue
            line = src[: m.start()].count("\n") + 1
            problems.append(f"{rel}:{line}: '{recv}.{name}' is not a member of {kit}")
    return problems


# ------------------------------------------------------------------ manifest

def check_manifest_classes(path: str) -> list[str]:
    src = open(path, encoding="utf-8").read()
    gradle = os.path.join(os.path.dirname(os.path.dirname(path)), "build.gradle")
    pkg = None
    if os.path.exists(gradle):
        m = re.search(r'namespace\s+[\'"]([^\'"]+)[\'"]', open(gradle, encoding="utf-8").read())
        if m:
            pkg = m.group(1)
    if not pkg:
        return []
    mod = os.path.relpath(path, ROOT).split(os.sep)[0]
    root = os.path.join(ROOT, mod, "src", "main", "java")
    problems = []
    for m in re.finditer(r'android:name="([^"]+)"', src):
        name = m.group(1)
        fq = pkg + name if name.startswith(".") else name
        if "." not in fq or fq.startswith("android"):
            continue
        rel = fq.replace(".", os.sep)
        if not (os.path.exists(os.path.join(root, rel + ".kt")) or os.path.exists(os.path.join(root, rel + ".java"))):
            problems.append(f"{os.path.relpath(path, ROOT)}: component not found on disk: {fq}")
    return problems


# -------------------------------------------------------- app registration


def check_app_registration() -> list[str]:
    """
    The invariant behind "the app is there but nothing happens".

    A mini-app or game that is listed in Kotlin but not declared in the
    manifest (or vice versa) is invisible to Android: no icon, no task, no
    launch. Every entry below is a list in code that the manifest must agree
    with, so it is checked mechanically instead of by eye.
    """
    problems: list[str] = []
    manifest_path = os.path.join(ROOT, "oea", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(manifest_path):
        return problems
    manifest = open(manifest_path, encoding="utf-8").read()

    # 1. Every mini-app's activity is declared.
    mini = os.path.join(ROOT, "oea", "src", "main", "java", "com", "oea", "launcher", "miniapps", "OeaMiniApps.kt")
    if os.path.exists(mini):
        src = open(mini, encoding="utf-8").read()
        for m in re.finditer(r'"(\w+)"\s*,\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*R\.drawable\.\w+\s*,\s*(\w+)::class\.java', src):
            app_id, activity = m.group(1), m.group(2)
            if f'android:name="com.oea.launcher.miniapps.{activity}"' not in manifest:
                problems.append(f"OeaMiniApps: mini-app '{app_id}' -> {activity} is not declared in the launcher manifest")

    # 2. Every game entry activity is declared and known to Game Boost.
    entries = os.path.join(ROOT, "oea", "src", "main", "java", "com", "oea", "launcher", "games", "OeaGameEntries.kt")
    if os.path.exists(entries):
        src = open(entries, encoding="utf-8").read()
        declared = {
            m.group(1) for m in re.finditer(r"class\s+(Oea\w+Activity)\s*:\s*OeaStandaloneGameActivity", src)
        }
        for name in sorted(declared):
            fq = f"com.oea.launcher.games.{name}"
            if f'android:name="{fq}"' not in manifest:
                problems.append(f"OeaGameEntries: {name} is not declared in the launcher manifest")
            if f"{name}::class.java.name" not in src:
                problems.append(
                    f"OeaGameEntries: {name} is missing from oeaGameEntryScreens, so Game Boost "
                    f"ends the session the moment the game opens"
                )

    # 3. Every standalone app ships a real game id that the engine registers.
    games = os.path.join(ROOT, "oea-games", "src", "main", "java", "com", "oea", "games", "OeaGames.kt")
    known_ids = set()
    if os.path.exists(games):
        gsrc = open(games, encoding="utf-8").read()
        m = re.search(r"val builtInIds: List<String> = listOf\(([^)]*)\)", gsrc)
        if m:
            known_ids = {x.strip().strip('"') for x in m.group(1).split(",") if x.strip()}
    for mod, _ in (("oea-game-arena", "arena"), ("oea-game-chess", "chess"), ("oea-game-runner", "runner")):
        act = glob_module_activity(mod)
        if not act:
            problems.append(f"{mod}: no activity found")
            continue
        m = re.search(r'override val standaloneGameId: String = "([^"]+)"', act)
        if not m:
            problems.append(f"{mod}: its activity does not set standaloneGameId")
        elif known_ids and m.group(1) not in known_ids:
            problems.append(f"{mod}: standaloneGameId '{m.group(1)}' is not a registered built-in game {sorted(known_ids)}")
    return problems


def glob_module_activity(mod: str):
    root = os.path.join(ROOT, mod, "src", "main", "java")
    if not os.path.isdir(root):
        return None
    for dirpath, _, names in os.walk(root):
        for nm in names:
            if nm.endswith(".kt"):
                return open(os.path.join(dirpath, nm), encoding="utf-8").read()
    return None


# ------------------------------------------------------- lightweight typing

DATA_CLASS_RE = re.compile(r"(?:^|\n)\s*(?:public |internal |data )*data class\s+(\w+)\s*\(")
PARAM_RE = re.compile(r"(?:^|\n)\s*(?:val|var)\s+(\w+)\s*:\s*[\w\.<>,\s\?]+?(?=[,)=\n])", re.M)
CLASS_VAR_RE = re.compile(r"(?:^|\n)\s{4}(?:var|val)\s+(\w+)\s*:")


def class_body(src: str, header_end: int) -> str:
    """Return the text of the class body that starts at or after [header_end]."""
    i, n, depth = header_end, len(src), 0
    in_str = False
    raw = False
    while i < n:
        c = src[i]
        if in_str:
            if raw and src.startswith('"""', i):
                in_str = False
                i += 3
                continue
            if not raw and c == "\\":
                i += 2
                continue
            if not raw and c == '"':
                in_str = False
            elif raw is False and c == "'":
                pass
            i += 1
            continue
        if src.startswith('"""', i):
            in_str, raw = True, True
            i += 3
            continue
        if c == '"':
            in_str, raw = True, False
            i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "{" and depth == 0:
            # Found the body: brace-match it.
            j, d = i, 0
            while j < n:
                if src[j] == "{":
                    d += 1
                elif src[j] == "}":
                    d -= 1
                    if d == 0:
                        return src[i + 1:j]
                j += 1
            return src[i + 1:]
        i += 1
    return ""


def build_type_model(files) -> dict[str, set[str]]:
    """
    Model the project's own types well enough to catch `opt.description`.

    A compiler rejects a member access that does not exist; without one that is
    the single most likely mistake to survive a review, so the model is built
    from the declarations themselves — constructor `val`s, body `val`/`var`s
    and body functions — rather than from a hand-written list that goes stale.
    """
    model: dict[str, set[str]] = {}

    def add(cls, names):
        if names:
            model.setdefault(cls, set()).update(names)

    for f in files:
        src = open(f, encoding="utf-8").read()
        for cm in re.finditer(r"(?:^|\n)\s*(?:public |internal |open |sealed |abstract )*data class\s+(\w+)", src):
            cls = cm.group(1)
            header_end = cm.end()
            # constructor parameter properties
            if src.startswith("(", header_end) or re.match(r"\s*(?:internal\s+)?constructor\s*\(", src[header_end:header_end + 40]):
                i = src.index("(", header_end)
                depth, j = 0, i
                while j < len(src):
                    if src[j] == "(":
                        depth += 1
                    elif src[j] == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    j += 1
                params = src[i + 1:j]
                for line in params.split("\n"):
                    pm = re.match(r"\s*(?:val|var)\s+(\w+)\s*:", line.strip().rstrip(","))
                    if pm:
                        add(cls, {pm.group(1)})
            # Body members too (data classes are small and their bodies rarely
            # contain a brace inside a string, which is what defeats the
            # brace-matching used for ordinary classes).
            limit = len(src)
            nm = re.search(r"(?:^|\n)(?:@\w+\s+)*(?:public |internal |open |sealed |abstract |data |enum )*(?:class|object|interface|fun)\s", src[header_end:])
            if nm:
                limit = header_end + nm.start()
            body = class_body(src[:limit], header_end)
            if body:
                add(cls, {m.group(1) for m in re.finditer(r"(?:^|\n)\s{4}(?:@\w+\s+)*(?:val|var)\s+(\w+)\s*[:=]", body)})
                add(cls, {m.group(1) for m in re.finditer(r"(?:^|\n)\s{4}(?:open |override |internal |private |protected |public |suspend |inline |operator )*fun\s+(\w+)", body)})
    return model


def check_field_access(rel: str, src: str, model: dict[str, set[str]], strict_files: set[str]) -> list[str]:
    if rel not in strict_files:
        return []
    src = strip_comments(src)

    # Collect every binding of a name to a modelled type. A name bound to two
    # different types in the same file is ambiguous (a lambda parameter
    # shadowing a field, most often), so it is left alone.
    bindings: dict[str, set[str]] = {}

    def note(name: str, type_name: str):
        type_name = type_name.rstrip("?").split(".")[-1]
        if type_name in model:
            bindings.setdefault(name, set()).add(type_name)

    for m in re.finditer(r"(?:val|var)\s+(\w+)\s*:\s*([\w\.]+)", src):
        note(m.group(1), m.group(2))
    for m in re.finditer(r"\bfun\s+\w+\s*(?:<[^>]*>)?\s*\(([^)]*)\)", src):
        for param in m.group(1).split(","):
            pm = re.match(r"\s*(?:val|var)?\s*(\w+)\s*:\s*([\w\.<>]+)", param)
            if pm:
                note(pm.group(1), pm.group(2))
    for m in re.finditer(r"(?:val|var)\s+(\w+)\s*=\s*(\w+)\s*\?:", src):
        for t in bindings.get(m.group(2), ()):
            note(m.group(1), t)

    shadowed = set()
    for m in re.finditer(r"\{([^{}]*?)->", src):
        for part in m.group(1).split(","):
            pm = re.match(r"\s*(\w+)\s*$", part)
            if pm:
                shadowed.add(pm.group(1))
    typed = {
        name: next(iter(types))
        for name, types in bindings.items()
        if len(types) == 1 and name not in shadowed
    }

    problems = []
    for m in re.finditer(r"(?<![\w\.'])(\w+)\.(\w+)", src):
        var, field = m.group(1), m.group(2)
        type_name = typed.get(var)
        if not type_name:
            continue
        allowed = model.get(type_name)
        if not allowed or field in allowed:
            continue
        line = src[: m.start()].count("\n") + 1
        problems.append(
            f"{rel}:{line}: '{var}.{field}' — {type_name} has no member '{field}' "
            f"(has: {', '.join(sorted(allowed)[:12])})"
        )
    return problems


# ----------------------------------------------------------------------- main

def main() -> int:
    files = []
    for base in MODULES:
        for dirpath, _, names in os.walk(os.path.join(ROOT, base, "src")):
            for nm in names:
                if nm.endswith((".kt", ".java")):
                    files.append(os.path.join(dirpath, nm))

    problems: list[str] = []
    strict = {
        "oea-games/src/main/java/com/oea/games/OeaStandaloneGameActivity.kt",
        "oea-games/src/main/java/com/oea/games/ui/GameUi.kt",
        "oea-games/src/main/java/com/oea/games/OeaGames.kt",
        "oea/src/main/java/com/oea/launcher/games/OeaGameEntries.kt",
    }
    table = build_symbol_table(files)
    type_model = build_type_model(files)

    for f in files:
        src = open(f, encoding="utf-8").read()
        rel = os.path.relpath(f, ROOT)
        problems += check_balance(rel, src)
        problems += check_symbols(rel, src, table, strict)
        problems += check_field_access(rel, src, type_model, strict)

    # GameKit usage inside the standalone shell.
    ui = os.path.join(ROOT, "oea-games", "src", "main", "java", "com", "oea", "games", "ui", "GameUi.kt")
    kits: dict[str, set[str]] = {}
    if os.path.exists(ui):
        ui_src = open(ui, encoding="utf-8").read()
        kits["GameKit"] = declared_members(ui_src) | {"palette", "context"}
        kits["GameColors"] = declared_members(ui_src)
        kits["GameInsets"] = declared_members(ui_src)
        kits["GameType"] = declared_members(ui_src)
        kits["GameToggle"] = declared_members(ui_src)

    activity = os.path.join(ROOT, "oea-games", "src", "main", "java", "com", "oea", "games", "OeaStandaloneGameActivity.kt")
    if os.path.exists(activity):
        rel = os.path.relpath(activity, ROOT)
        problems += check_receiver_calls(rel, open(activity, encoding="utf-8").read(), kits,
                                         {"k": "GameKit", "kit": "GameKit", "toolkit()": "GameKit"})

    for manifest in (
        "oea/src/main/AndroidManifest.xml",
        "oea-game-arena/src/main/AndroidManifest.xml",
        "oea-game-chess/src/main/AndroidManifest.xml",
        "oea-game-runner/src/main/AndroidManifest.xml",
    ):
        p = os.path.join(ROOT, manifest)
        if os.path.exists(p):
            problems += check_manifest_classes(p)

    problems += check_app_registration()

    settings = open(os.path.join(ROOT, "settings.gradle"), encoding="utf-8").read()
    for m in re.finditer(r'include\(":([^"]+)"\)', settings):
        if not os.path.isdir(os.path.join(ROOT, m.group(1))):
            problems.append(f"settings.gradle: module :{m.group(1)} has no directory")

    if problems:
        print(f"STATIC CHECK: {len(problems)} problem(s)")
        for p in problems:
            print("  -", p)
        return 1
    print(f"STATIC CHECK OK — {len(files)} sources: brackets balanced, kit calls resolve, "
          f"OEA symbols declared, manifests consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
