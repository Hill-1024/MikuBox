#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Locale key coverage check for mikuray-ui resources.

Compares every locale directory (res/values-*) against the base (res/values)
across all translatable resource names: <string>, <plurals> and
<string-array>/<integer-array>. Missing keys are reported per locale; with
--apply <file> the missing keys of ONE locale (--locale) are backfilled from a
JSON file {key: {"strings": "..."} | {"plurals": {quantity: text}}}, appended
before </resources> of the matching file (falling back to strings.xml).

--ci  exits 1 when any locale is missing any key (for CI gates).
"""
import argparse
import glob
import io
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "mikuray-ui", "src", "main", "res"))
NAME_RE = re.compile(r'name="([^"]+)"')

def collect(directory):
    """Return {resource_name: (kind, file_path)} for one res directory."""
    keys = {}
    for path in sorted(glob.glob(os.path.join(directory, "*.xml"))):
        if os.path.basename(path) == "attrs.xml":
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            print("PARSE ERROR %s: %s" % (path, e), file=sys.stderr)
            continue
        for element in root:
            name = element.get("name")
            if not name:
                continue
            if element.tag in ("string", "plurals", "string-array", "integer-array"):
                kind = "plurals" if element.tag == "plurals" else "string"
                if element.tag in ("string-array", "integer-array"):
                    kind = "array"
                keys[name] = (kind, path)
    return keys

def base_value(name, kind):
    """Best-effort base text for a key, for translation reference dumps."""
    for path in sorted(glob.glob(os.path.join(RES, "values", "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for element in root:
            if element.get("name") != name:
                continue
            if element.tag == "plurals":
                return {item.get("quantity"): (item.text or "") for item in element}
            if element.tag in ("string-array", "integer-array"):
                return [(item.text or "") for item in element]
            return element.text or ""
    return None

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", metavar="JSON", help="backfill missing keys of --locale from this JSON file")
    parser.add_argument("--locale", metavar="DIR", help="locale directory name, e.g. values-in")
    parser.add_argument("--dump", metavar="FILE", help="write missing keys + base values (JSON) for --locale")
    parser.add_argument("--ci", action="store_true", help="exit 1 on any missing key")
    args = parser.parse_args()

    base = collect(os.path.join(RES, "values"))
    locales = sorted(
        os.path.basename(d) for d in glob.glob(os.path.join(RES, "values-*"))
        if os.path.basename(d) not in ("values-night",)
    )

    if args.apply:
        if not args.locale:
            parser.error("--apply needs --locale")
        with io.open(args.apply, "r", encoding="utf-8") as f:
            translations = json.load(f)
        directory = os.path.join(RES, args.locale)
        locale_keys = collect(directory)
        added = 0
        for name, value in translations.items():
            if name in locale_keys:
                continue
            kind = base.get(name, ("string", None))[0]
            if kind == "plurals" and isinstance(value, dict):
                items = "".join(
                    '        <item quantity="%s">%s</item>\n' % (q, esc(t))
                    for q, t in sorted(value.items())
                )
                entry = '    <plurals name="%s">\n%s    </plurals>\n' % (name, items)
            elif kind == "array" and isinstance(value, list):
                items = "".join("        <item>%s</item>\n" % esc(t) for t in value)
                entry = '    <string-array name="%s" translatable="false">\n%s    </string-array>\n' % (name, items)
            else:
                text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
                entry = '    <string name="%s">%s</string>\n' % (name, esc(text))
            target = locale_keys.get(name, (None, os.path.join(directory, "strings.xml")))[1]
            with io.open(target, "r", encoding="utf-8") as f:
                content = f.read()
            idx = content.rfind("</resources>")
            content = content[:idx] + entry + content[idx:]
            with io.open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
            locale_keys[name] = (kind, target)
            added += 1
        print("added %d keys to %s" % (added, args.locale))
        return 0

    missing_all = {}
    for locale in locales:
        have = collect(os.path.join(RES, locale))
        missing = sorted(set(base) - set(have))
        missing_all[locale] = missing
        print("%s: %d missing" % (locale, len(missing)))
        for name in missing:
            print("  %s" % name)

    if args.dump:
        if not args.locale:
            parser.error("--dump needs --locale")
        payload = {}
        for name in missing_all.get(args.locale, []):
            payload[name] = {"kind": base[name][0], "base": base_value(name, base[name][0])}
        with io.open(args.dump, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=1)
        print("dumped %d entries to %s" % (len(payload), args.dump))

    if args.ci:
        total = sum(len(v) for v in missing_all.values())
        if total:
            print("CI FAIL: %d keys missing across locales" % total, file=sys.stderr)
            return 1
        print("locale coverage OK")
    return 0

_APT_APOS = re.compile(r"(?<!\\)'")

def esc(text):
    # XML-escape entities (ElementTree hands us decoded text), then escape
    # apostrophes for AAPT — but only ones not already escaped: base values
    # arrive pre-escaped (Emily\'s Candy) and double-escaping produces an
    # unescaped apostrophe error in aapt2 compile.
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return _APT_APOS.sub(lambda m: "\\" + "'", text)

if __name__ == "__main__":
    sys.exit(main())
