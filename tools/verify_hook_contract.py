# -*- coding: utf-8 -*-
"""Verify the OPlus hook contract against the real SystemUI dex files.

TileBackgroundHook.java addresses ColorOS internals by hard-coded class, method
and field names. If any of them is wrong the hook silently does nothing, which is
very hard to notice on a device. This script cross-checks the names used by the
hook against the classes actually present in the SystemUI APK:

  1. names are re-parsed from TileBackgroundHook.java (the source of truth),
  2. every referenced class must exist in the SystemUI dex,
  3. every hook target method must exist on that class or one of its supers,
  4. every field read through reflection must exist on its class,
  5. every "com.oplus.*" literal inside the built APK's classes.dex must resolve,
     which proves the shipped artifact has no stale names. A name resolves when the
     SystemUI dex defines it *or* references it as a type: platform classes such as
     com.oplus.graphics.OplusPath live in the framework, not in the APK, but
     SystemUI itself refers to them, which is proof enough that the name is real.

Usage:
  python tools/verify_hook_contract.py [--systemui <dir>]

The SystemUI dex directory is NOT part of this repository (it is a vendor
artifact). By default the script looks for it next to the project root; pass
--systemui to point at your own decompiled directory.

Exit code 0 = contract holds, 1 = mismatches found, 2 = SystemUI dex dir missing.
"""
import argparse
import io
import logging
import os
import re
import sys
import zipfile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
logging.disable(logging.CRITICAL)

from androguard.core.dex import DEX

# 项目根 = 本脚本所在目录（tools/）的上一级，跟 checkout 到哪无关。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOOK_JAVA = os.path.join(
    ROOT, "app", "src", "main", "java",
    "com", "thexiaoxia43", "coloros", "theme", "TileBackgroundHook.java")
HOOK_APK = os.path.join(ROOT, "build", "SystemUIHook.apk")

# 反编译后的 SystemUI dex 目录名。默认在项目同级目录找，可用 --systemui 覆盖。
SYSTEMUI_DIR_NAME = "系统界面_16.00.12_unpacked"
DEFAULT_SYSTEMUI_DIR = os.path.join(os.path.dirname(ROOT), SYSTEMUI_DIR_NAME)
SYSTEMUI_DIR = DEFAULT_SYSTEMUI_DIR

# Field reads performed via XposedHelpers.getObjectField(owner, field).
FIELD_CONTRACT = [
    ("com.oplus.systemui.qs.base.widget.QsViewBackgroundProxy", "qsView"),
    ("com.oplus.systemui.qs.base.widget.QsStaticBackgroundProxy", "panelInfo"),
    ("com.oplus.systemui.qs.base.widget.BaseQsViewBackground", "qsView"),
    ("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView", "mBackgroundProxy"),
]

# Methods invoked on the class looked up in FIELD_CONTRACT (owner, method).
CALL_CONTRACT = [
    ("com.oplus.systemui.qs.base.widget.QsViewInfoProvider", "getBackgroundView"),
    ("com.oplus.systemui.qs.base.widget.QsViewBackgroundProxy", "refreshViewBackground"),
]

CONST_RE = re.compile(r'String\s+(\w+)\s*=\s*"([^"]*)"\s*;')
ARRAY_RE = re.compile(r'String\[\]\s+(\w+)\s*=\s*\{(.*?)\}\s*;', re.DOTALL)
TYPE_RE = re.compile(r'^Lcom/oplus/[\w/$]+;$')

REPORT_PATH = os.path.join(ROOT, "verify_hook_contract_report.txt")

failures = []
checks = 0
report = []


def emit(line):
    """Write to stdout and to the UTF-8 report file at the same time."""
    print(line)
    report.append(line)


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        failures.append(message)
    return condition


def to_dot(dex_name):
    """Lcom/foo/Bar; -> com.foo.Bar"""
    return dex_name[1:-1].replace("/", ".")


def load_systemui(classes):
    """name -> {'super', 'methods', 'fields', 'descriptors'} for every SystemUI class."""
    for filename in sorted(os.listdir(SYSTEMUI_DIR)):
        if not re.match(r"classes\d*\.dex$", filename):
            continue
        with open(os.path.join(SYSTEMUI_DIR, filename), "rb") as handle:
            dex = DEX(handle.read())
        for cls in dex.get_classes():
            name = to_dot(cls.get_name())
            if name in classes:
                continue
            super_name = cls.get_superclassname()
            descriptors = {}
            for method in cls.get_methods():
                descriptors.setdefault(method.get_name(), set()).add(
                    method.get_descriptor())
            classes[name] = {
                "super": to_dot(super_name) if super_name else None,
                "methods": set(descriptors),
                "fields": set(f.get_name() for f in cls.get_fields()),
                "descriptors": descriptors,
            }
    return classes


def load_referenced_types():
    """SystemUI dex 里被引用过的 com.oplus.* 类型（不要求在 APK 里有定义）。

    平台类（framework 里的 {@code com.oplus.graphics.OplusPath} 之类）不在 APK 的 dex
    里定义，但 SystemUI 自己会引用它们；引用到了就说明这个名字是真的。
    """
    referenced = set()
    for filename in sorted(os.listdir(SYSTEMUI_DIR)):
        if not re.match(r"classes\d*\.dex$", filename):
            continue
        with open(os.path.join(SYSTEMUI_DIR, filename), "rb") as handle:
            dex = DEX(handle.read())
        for item in dex.get_strings():
            value = item.get_value() if hasattr(item, "get_value") else str(item)
            if TYPE_RE.match(value):
                referenced.add(to_dot(value))
    return referenced


def resolve_expression(expression, constants):
    """Evaluate a Java constant expression such as CONST_NAME + "$Inner"."""
    parts = [p.strip() for p in expression.split("+") if p.strip()]
    if not parts:
        return None
    value = ""
    for part in parts:
        if part.startswith('"') and part.endswith('"'):
            value += part[1:-1]
        elif part in constants:
            value += constants[part]
        else:
            return None
    return value


def parse_java_contract(path):
    """Extract the class constants and the String[] bodies from the hook source."""
    with open(path, "r", encoding="utf-8") as handle:
        source = handle.read()
    constants = dict(CONST_RE.findall(source))
    arrays = {}
    for name, body in ARRAY_RE.findall(source):
        values = []
        for element in body.split(","):
            element = element.strip()
            if not element:
                continue
            resolved = resolve_expression(element, constants)
            if resolved:
                values.append(resolved)
        arrays[name] = values
    return constants, arrays


def method_exists(classes, class_name, method_name):
    """True when the method resolves on the class or on any of its supers."""
    seen = set()
    current = class_name
    while current and current not in seen:
        seen.add(current)
        info = classes.get(current)
        if info is None:
            return False
        if method_name in info["methods"]:
            return True
        current = info["super"]
    return False


def declaring_class(classes, class_name, method_name):
    """The class that actually declares the method, walking up the hierarchy."""
    seen = set()
    current = class_name
    while current and current not in seen:
        seen.add(current)
        info = classes.get(current)
        if info is None:
            return None
        if method_name in info["methods"]:
            return current
        current = info["super"]
    return None


def field_exists(classes, class_name, field_name):
    """True when the field resolves on the class or on any of its supers."""
    seen = set()
    current = class_name
    while current and current not in seen:
        seen.add(current)
        info = classes.get(current)
        if info is None:
            return False
        if field_name in info["fields"]:
            return True
        current = info["super"]
    return False


def descriptor_exists(classes, class_name, method_name, descriptor):
    """True when method_name with that exact JVM descriptor resolves."""
    seen = set()
    current = class_name
    while current and current not in seen:
        seen.add(current)
        info = classes.get(current)
        if info is None:
            return False
        if descriptor in info["descriptors"].get(method_name, ()):
            return True
        current = info["super"]
    return False


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="把 hook 源码里硬编码的 com.oplus.* 类名 / 方法名 / 字段名"
                    "与真实 SystemUI dex 对照，防止改名导致 hook 静默失效。")
    parser.add_argument(
        "--systemui", metavar="DIR", default=DEFAULT_SYSTEMUI_DIR,
        help="反编译后的 SystemUI dex 目录（含 classes*.dex）。"
             "本仓库不含这个目录（厂商版权产物），请自备。默认：%s"
             % DEFAULT_SYSTEMUI_DIR)
    return parser.parse_args(argv)


def main(argv=None):
    global SYSTEMUI_DIR
    args = parse_args(sys.argv[1:] if argv is None else argv)
    SYSTEMUI_DIR = args.systemui
    if not os.path.isdir(SYSTEMUI_DIR):
        print("找不到 SystemUI dex 目录：%s" % SYSTEMUI_DIR)
        print("该目录是反编译 SystemUI 得到的产物（里面应有 classes*.dex），"
              "包含厂商版权内容，本仓库不提供。")
        print("请自备并用 --systemui 指定，例如：")
        print("  python tools/verify_hook_contract.py "
              "--systemui <反编译目录>")
        return 2

    emit("SystemUI dex dir : %s" % SYSTEMUI_DIR)
    emit("hook source      : %s" % HOOK_JAVA)
    classes = load_systemui({})
    referenced_types = load_referenced_types()
    emit("SystemUI classes : %d" % len(classes))
    emit("SystemUI refs    : %d oplus types" % len(referenced_types))

    constants, arrays = parse_java_contract(HOOK_JAVA)
    factory_class_names = arrays.get("DRAWABLE_FACTORY_CLASSES", [])
    factory_methods = arrays.get("DRAWABLE_FACTORY_METHODS", [])
    refresh_methods = arrays.get("REFRESH_METHODS", [])
    color_class_names = arrays.get("COLOR_UPDATE_CLASSES", [])
    color_update_params = arrays.get("COLOR_UPDATE_METHODS", [])
    emit("hook classes     : %d drawable / %d color"
         % (len(factory_class_names), len(color_class_names)))
    emit("hook methods     : %s" % ", ".join(factory_methods + refresh_methods + color_update_params))
    emit("-" * 100)

    # 1. every hook constant that names an OPlus class must exist
    for name, value in sorted(constants.items()):
        if value.startswith("com.oplus."):
            check(value in classes,
                  "constant %s -> %s not found in SystemUI" % (name, value))

    # 2. every factory class exists and implements the obtainDrawableFor* API
    for class_name in factory_class_names:
        if not check(class_name in classes,
                     "factory class missing in SystemUI: %s" % class_name):
            continue
        for method_name in factory_methods:
            check(method_exists(classes, class_name, method_name),
                  "method missing: %s#%s" % (class_name, method_name))
        for method_name in refresh_methods:
            check(method_exists(classes, class_name, method_name),
                  "refresh method missing: %s#%s" % (class_name, method_name))

    # 3. reflected field reads
    for class_name, field_name in FIELD_CONTRACT:
        info = classes.get(class_name)
        if not check(info is not None, "field owner missing: %s" % class_name):
            continue
        check(field_name in info["fields"],
              "field missing: %s#%s" % (class_name, field_name))

    # 4. method calls performed on reflected owners
    for class_name, method_name in CALL_CONTRACT:
        check(class_name in classes and method_exists(classes, class_name, method_name),
              "call target missing: %s#%s" % (class_name, method_name))

    # 5. the built APK must not contain names that no longer resolve
    if os.path.isfile(HOOK_APK):
        with zipfile.ZipFile(HOOK_APK) as archive:
            dex_bytes = archive.read("classes.dex")
        apk_dex = DEX(dex_bytes)
        literals = set()
        for item in apk_dex.get_strings():
            value = item.get_value() if hasattr(item, "get_value") else str(item)
            literals.add(value)
        oplus_literals = [s for s in literals if s.startswith("com.oplus.")]
        emit("APK oplus refs   : %d" % len(oplus_literals))
        for literal in sorted(oplus_literals):
            check(literal in classes or literal in referenced_types,
                  "stale name in built APK: %s" % literal)
    else:
        emit("APK not built yet, skipping shipped-artifact check: %s" % HOOK_APK)

    emit("-" * 100)
    if failures:
        emit("FAILED  %d/%d checks" % (len(failures), checks))
        for failure in failures:
            emit("  - %s" % failure)
        status = 1
    else:
        emit("PASSED  %d/%d checks" % (checks, checks))
        status = 0

    with open(REPORT_PATH, "w", encoding="utf-8") as handle:
        handle.write("\n".join(report) + "\n")
    print("report written to %s" % REPORT_PATH)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))