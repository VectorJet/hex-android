#!/usr/bin/env python3
"""Compiles FUTO Keyboard's YAML layout definitions and locale text tables into
compact JSON assets (`futo_layouts.json` and `futo_texts.json`) for Hex Android."""

import glob
import json
import os
import yaml


class Yaml12Loader(yaml.SafeLoader):
    pass


Yaml12Loader.add_constructor(
    "tag:yaml.org,2002:value", lambda loader, node: loader.construct_scalar(node)
)


META_DIRECTIVES = (
    "!fixedColumnOrder!",
    "!autoColumnOrder!",
    "!hasLabels!",
    "!needsDividers!",
    "!noPanelAutoMoreKey!",
)


def unescape_aosp(s: str) -> str:
    """Unescapes AOSP keyspec backslash escapes (e.g. \\\\ -> \\, \\| -> |, \\' -> ')."""
    out = []
    i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            out.append(s[i + 1])
            i += 2
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def split_unescaped_pipe(s: str) -> tuple[str, str] | None:
    """Splits on the first unescaped '|' if present."""
    i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            i += 2
            continue
        if s[i] == "|":
            return s[:i], s[i + 1 :]
        i += 1
    return None


def parse_spec(raw: str) -> dict:
    s = str(raw)
    if s == "$":
        return {"kind": "key", "label": "$", "output": "$"}
    if s.startswith("$"):
        token = s[1:]
        if token in (
            "shift",
            "delete",
            "space",
            "enter",
            "symbols",
            "alphabet",
            "number",
            "contextual",
            "action",
            "optionalzwnj",
            "zwnj",
            "gap",
        ):
            return {"kind": token}
        if token == "currency1":
            return {"kind": "key", "label": "£", "output": "£"}
        if token == "currency2":
            return {"kind": "key", "label": "¢", "output": "¢"}
        if token == "currency3":
            return {"kind": "key", "label": "€", "output": "€"}
        if token == "currency4":
            return {"kind": "key", "label": "¥", "output": "¥"}
        if token.startswith("alt"):
            return {"kind": "symbols"}

    split = split_unescaped_pipe(s)
    if split is not None:
        left, right = split
        if right.startswith("!code/"):
            if right == "!code/key_switch_alpha_symbol":
                return {"kind": "symbols"}
            if right == "!code/key_to_number_layout":
                return {"kind": "number"}
            if right == "!code/key_space":
                return {"kind": "space"}
            if right == "!code/key_delete":
                return {"kind": "delete"}
            if right == "!code/key_enter":
                return {"kind": "enter"}
            if right == "!code/key_shift":
                return {"kind": "shift"}
            if right == "!code/action_emoji":
                return {"kind": "action"}
            return {"kind": "ignore"}
        label = left
        if label.startswith("!icon/"):
            icon_name = label.removeprefix("!icon/")
            if icon_name == "zwnj_key":
                label = "ZWNJ"
            elif icon_name == "zwj_key":
                label = "ZWJ"
            else:
                label = unescape_aosp(right)
        elif label.startswith("!string/"):
            str_name = label.removeprefix("!string/")
            label = {"label_pause_key": "Pause", "label_wait_key": "Wait"}.get(
                str_name, unescape_aosp(right)
            )
        else:
            label = unescape_aosp(label)
        return {"kind": "key", "label": label, "output": unescape_aosp(right)}

    if s.startswith("!icon/") or s.startswith("!code/"):
        return {"kind": "ignore"}
    if s.startswith("!text/"):
        return {"kind": "key", "label": s, "output": s}
    val = unescape_aosp(s)
    return {"kind": "key", "label": val, "output": val}


def clean_more_keys(items) -> list[str]:
    if not items:
        return []
    if isinstance(items, str):
        items = [x for x in items.split(" ") if x]
    out = []
    for item in items:
        s = str(item)
        if s == "%" or any(s.startswith(d) for d in META_DIRECTIVES):
            continue
        if s.startswith("!icon/") and "|" not in s:
            continue
        if s.startswith("!code/"):
            continue
        split = split_unescaped_pipe(s)
        if split is not None:
            left, right = split
            if right.startswith("!code/"):
                continue
            out.append(f"{unescape_aosp(left)}|{unescape_aosp(right)}")
        elif s.startswith("!text/"):
            out.append(s)
        else:
            out.append(unescape_aosp(s))
    return out


def parse_key_node(node, inherited_shiftable: bool = True) -> dict | None:
    if node is None:
        return None
    if isinstance(node, str):
        parsed = parse_spec(node)
        if parsed["kind"] == "ignore":
            return None
        if parsed["kind"] != "key":
            return parsed
        return {
            "kind": "key",
            "label": parsed["label"],
            "output": parsed["output"],
            "shiftable": inherited_shiftable,
            "moreKeys": [],
        }
    if isinstance(node, list):
        if not node:
            return None
        primary = parse_key_node(node[0], inherited_shiftable)
        if primary is None:
            return None
        if primary.get("kind") == "key":
            extra = clean_more_keys(node[1:])
            primary["moreKeys"] = primary.get("moreKeys", []) + extra
        return primary
    if isinstance(node, dict):
        attrs = node.get("attributes") or {}
        shiftable = attrs.get("shiftable", inherited_shiftable)
        ktype = node.get("type", "base")
        if ktype in ("shift", "delete", "enter", "space", "symbols", "alphabet", "number", "gap"):
            return {"kind": ktype}
        if ktype == "contextual":
            fb = parse_key_node(node.get("fallbackKey"), shiftable)
            return fb if fb else {"kind": "contextual"}
        if ktype in ("action", "actionKeySelect"):
            return {"kind": "action"}
        if ktype == "case":
            normal_node = parse_key_node(node.get("normal"), shiftable)
            shifted_raw = node.get("shifted") if "shifted" in node else node.get("shiftedManually")
            shifted_node = parse_key_node(shifted_raw, False)
            if normal_node is None and shifted_node is None:
                return None
            base = normal_node or shifted_node
            if base.get("kind") != "key":
                return base
            res = dict(base)
            if shifted_node and shifted_node.get("kind") == "key":
                res["shiftedLabel"] = shifted_node["label"]
                res["shiftedOutput"] = shifted_node["output"]
                res["shiftedMoreKeys"] = shifted_node.get("moreKeys", [])
            if "hint" in node and node["hint"] is not None:
                res["hint"] = str(node["hint"])
            return res
        if ktype == "flick":
            primary_raw = node.get("primary") if "primary" in node else node.get("none")
            base = parse_key_node(primary_raw, shiftable)
            if base is None:
                return None
            if base.get("kind") == "key":
                dirs = []
                for d in ("up", "down", "left", "right"):
                    dn = parse_key_node(node.get(d), shiftable)
                    if dn and dn.get("kind") == "key" and dn["output"] not in dirs:
                        dirs.append(dn["output"])
                base["moreKeys"] = base.get("moreKeys", []) + dirs
            return base
        spec = node.get("spec")
        if spec is None:
            return None
        parsed = parse_spec(str(spec))
        if parsed["kind"] == "ignore":
            return None
        if parsed["kind"] != "key":
            return parsed
        res = {
            "kind": "key",
            "label": parsed["label"],
            "output": parsed["output"],
            "shiftable": bool(shiftable),
            "moreKeys": clean_more_keys(node.get("moreKeys")),
        }
        if "hint" in node and node["hint"] is not None:
            res["hint"] = str(node["hint"])
        return res
    return None


def expand_row_items(items, inherited_shiftable: bool) -> list[dict]:
    out = []
    if isinstance(items, str):
        tokens = [t for t in items.split(" ") if t]
        for tok in tokens:
            k = parse_key_node(tok, inherited_shiftable)
            if k:
                out.append(k)
        return out
    if isinstance(items, list):
        for elem in items:
            if isinstance(elem, str) and " " in elem.strip() and not elem.strip().startswith("!"):
                for tok in elem.strip().split(" "):
                    if tok:
                        k = parse_key_node(tok, inherited_shiftable)
                        if k:
                            out.append(k)
            else:
                k = parse_key_node(elem, inherited_shiftable)
                if k:
                    out.append(k)
    return out


def compile_layouts(repo_root: str) -> None:
    layouts_dir = os.path.join(repo_root, "refs/futo-keyboard/java/assets/layouts")
    locales_dir = os.path.join(repo_root, "refs/futo-keyboard/tools/make-keyboard-text-py/locales")
    out_dir = os.path.join(repo_root, "app/src/main/assets/layouts")
    os.makedirs(out_dir, exist_ok=True)

    texts = {}
    for path in sorted(glob.glob(os.path.join(locales_dir, "*.json"))):
        code = os.path.splitext(os.path.basename(path))[0]
        raw = json.load(open(path, encoding="utf-8"))
        table = {}
        for k, v in raw.get("keyspec", {}).items():
            if isinstance(v, str) and v:
                table[f"keyspec_{k}"] = unescape_aosp(v) if not v.startswith("!") else v
        for k, v in raw.get("morekeys", {}).items():
            if isinstance(v, list):
                cleaned = [
                    unescape_aosp(x) if not x.startswith("!") else x
                    for x in v
                    if isinstance(x, str) and x != "%" and not any(x.startswith(d) for d in META_DIRECTIVES)
                ]
                if cleaned:
                    table[f"morekeys_{k}"] = cleaned
        for k, v in raw.get("other", {}).items():
            if isinstance(v, str) and v:
                table[k] = v
        for k, v in raw.get("keylabel", {}).items():
            if isinstance(v, str) and v:
                table[f"keylabel_{k}"] = v
        texts[code] = table

    with open(os.path.join(out_dir, "futo_texts.json"), "w", encoding="utf-8") as f:
        json.dump(texts, f, ensure_ascii=False, separators=(",", ":"))

    layouts = {}
    for path in sorted(glob.glob(os.path.join(layouts_dir, "**/*.yaml"), recursive=True)):
        base = os.path.basename(path)
        if base in ("mapping.yaml", "names.yaml"):
            continue
        rel_dir = os.path.basename(os.path.dirname(path))
        if rel_dir == "Special":
            continue
        layout_id = os.path.splitext(base)[0]
        data = yaml.load(open(path, encoding="utf-8"), Loader=Yaml12Loader)
        if not isinstance(data, dict) or "rows" not in data:
            continue

        top_attrs = data.get("attributes") or {}
        inherited_shiftable = bool(top_attrs.get("shiftable", True))
        auto_shift = bool(data.get("autoShift", inherited_shiftable))
        number_row_mode = str(data.get("numberRowMode", "UserConfigurable"))

        compiled_rows = []
        for r in data.get("rows", []):
            if not isinstance(r, dict):
                continue
            row_attrs = r.get("attributes") or {}
            row_shiftable = bool(row_attrs.get("shiftable", inherited_shiftable))
            if "numbers" in r and r["numbers"] is not None:
                keys = expand_row_items(r["numbers"], row_shiftable)
                if keys:
                    compiled_rows.append({"type": "numbers", "keys": keys})
            elif "letters" in r and r["letters"] is not None:
                keys = expand_row_items(r["letters"], row_shiftable)
                if keys:
                    compiled_rows.append({"type": "letters", "keys": keys})
            elif "bottom" in r and r["bottom"] is not None:
                keys = expand_row_items(r["bottom"], row_shiftable)
                if keys:
                    compiled_rows.append({"type": "bottom", "keys": keys})

        if not any(row["type"] == "letters" for row in compiled_rows):
            continue

        layouts[layout_id] = {
            "id": layout_id,
            "name": str(data.get("name", layout_id)),
            "autoShift": auto_shift,
            "shiftable": inherited_shiftable,
            "numberRowMode": number_row_mode,
            "rows": compiled_rows,
        }

    with open(os.path.join(out_dir, "futo_layouts.json"), "w", encoding="utf-8") as f:
        json.dump(layouts, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Compiled {len(layouts)} layouts and {len(texts)} locale text tables into {out_dir}")


if __name__ == "__main__":
    compile_layouts("/home/tammy/dev/projects/hex-android")
