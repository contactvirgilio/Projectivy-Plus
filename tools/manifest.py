#!/usr/bin/env python3
"""Writes the binary resources Lite Home needs, without aapt:
  AndroidManifest.xml, res/xml/accessibility.xml and a one-entry resources.arsc.

Usage: manifest.py APK_DIR
"""
import os
import struct
import sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Framework attribute IDs (android.R.attr)
ATTR = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "banner": 0x010103f2,
    "permission": 0x01010006, "exported": 0x01010010,
    "clearTaskOnLaunch": 0x01010015, "stateNotNeeded": 0x01010016,
    "launchMode": 0x0101001d, "screenOrientation": 0x0101001e,
    "configChanges": 0x0101001f, "description": 0x01010020,
    "resource": 0x01010025, "minSdkVersion": 0x0101020c,
    "versionCode": 0x0101021b, "versionName": 0x0101021c,
    "targetSdkVersion": 0x01010270, "required": 0x0101028e,
    "accessibilityEventTypes": 0x01010380, "accessibilityFeedbackType": 0x01010382,
    "notificationTimeout": 0x01010383, "accessibilityFlags": 0x01010384,
    "canRequestFilterKeyEvents": 0x010103d9, "canRetrieveWindowContent": 0x01010385,
}
T_REF, T_STR, T_INT, T_HEX, T_BOOL = 0x01, 0x03, 0x10, 0x11, 0x12
THEME_FULLSCREEN = 0x0103012a  # Theme.DeviceDefault.NoActionBar.Fullscreen

PKG = "io.lite.tvhome"
VERSION_CODE, VERSION_NAME = 12, "1.12"
ACCESSIBILITY_XML_PATH = "res/xml/accessibility.xml"
ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "art")

# Every resource in the APK: (type, name, path in APK, screen density or 0 for any, source file).
# IDs are 0x7f TT EEEE, with types and entries numbered in the order they appear here.
RESOURCES = [
    ("xml", "accessibility", ACCESSIBILITY_XML_PATH, 0, None),
    ("drawable", "icon", "res/drawable-xxxhdpi-v4/icon.png", 640, "icon.png"),        # 192 px = 48 dp
    ("drawable", "banner", "res/drawable-xhdpi-v4/banner.jpg", 320, "banner.jpg"),    # 640x360 = 320x180 dp
]


def res_id(type_, name):
    types = []
    for t, n, _, _, _ in RESOURCES:
        if t not in types:
            types.append(t)
    entries = [n for t, n, _, _, _ in RESOURCES if t == type_]
    return 0x7f000000 | ((types.index(type_) + 1) << 16) | entries.index(name)


ACCESSIBILITY_XML_ID = res_id("xml", "accessibility")
ICON_ID = res_id("drawable", "icon")
BANNER_ID = res_id("drawable", "banner")

# (tag, [(attr, type, value)], children)  attr without android: prefix unless "package"
MANIFEST = ("manifest", [("package", T_STR, PKG), ("versionCode", T_INT, VERSION_CODE),
                         ("versionName", T_STR, VERSION_NAME)], [
    ("uses-sdk", [("minSdkVersion", T_INT, 21), ("targetSdkVersion", T_INT, 29)], []),
    ("uses-permission", [("name", T_STR, "android.permission.REQUEST_DELETE_PACKAGES")], []),
    ("uses-feature", [("name", T_STR, "android.software.leanback"), ("required", T_BOOL, False)], []),
    ("uses-feature", [("name", T_STR, "android.hardware.touchscreen"), ("required", T_BOOL, False)], []),
    ("application", [("theme", T_REF, THEME_FULLSCREEN), ("label", T_STR, "Lite Home"),
                     ("icon", T_REF, ICON_ID), ("banner", T_REF, BANNER_ID)], [
        ("activity", [
            ("theme", T_REF, THEME_FULLSCREEN),
            ("label", T_STR, "Lite Home"),
            ("name", T_STR, PKG + ".Home"),
            ("clearTaskOnLaunch", T_BOOL, True),
            ("stateNotNeeded", T_BOOL, True),
            ("launchMode", T_INT, 2),            # singleTask
            ("screenOrientation", T_INT, 0),     # landscape
            ("configChanges", T_HEX, 0xFF0),
        ], [
            ("intent-filter", [], [
                ("action", [("name", T_STR, "android.intent.action.MAIN")], []),
                ("category", [("name", T_STR, "android.intent.category.HOME")], []),
                ("category", [("name", T_STR, "android.intent.category.DEFAULT")], []),
                ("category", [("name", T_STR, "android.intent.category.LAUNCHER")], []),
                ("category", [("name", T_STR, "android.intent.category.LEANBACK_LAUNCHER")], []),
            ]),
        ]),
        # "Override current launcher" accessibility service.
        ("service", [
            ("label", T_STR, "Lite Home override"),
            ("name", T_STR, PKG + ".HomeOverride"),
            ("permission", T_STR, "android.permission.BIND_ACCESSIBILITY_SERVICE"),
            ("exported", T_BOOL, True),
        ], [
            ("intent-filter", [], [
                ("action", [("name", T_STR, "android.accessibilityservice.AccessibilityService")], []),
            ]),
            ("meta-data", [("name", T_STR, "android.accessibilityservice"),
                           ("resource", T_REF, ACCESSIBILITY_XML_ID)], []),
        ]),
    ]),
])

ACCESSIBILITY = ("accessibility-service", [
    ("description", T_STR, "Opens Lite Home when you press the Home button, enforces app locks "
                           "and opens the power menu. Reads no screen content."),
    ("accessibilityEventTypes", T_HEX, 0x20),     # typeWindowStateChanged
    ("accessibilityFeedbackType", T_HEX, 0x10),   # feedbackGeneric
    ("notificationTimeout", T_INT, 0),
    ("accessibilityFlags", T_HEX, 0x20),          # flagRequestFilterKeyEvents
    ("canRequestFilterKeyEvents", T_BOOL, True),
], [])


class Pool:
    def __init__(self):
        self.strings, self.index = [], {}

    def add(self, s):
        if s not in self.index:
            self.index[s] = len(self.strings)
            self.strings.append(s)
        return self.index[s]

    def chunk(self):
        data, offsets = b"", []
        for s in self.strings:
            offsets.append(len(data))
            data += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\x00\x00"
        while len(data) % 4:
            data += b"\x00"
        start = 28 + 4 * len(offsets)
        out = struct.pack("<HHIIIIII", 0x0001, 28, start + len(data), len(self.strings), 0, 0, start, 0)
        return out + b"".join(struct.pack("<I", o) for o in offsets) + data


def axml(tree):
    pool = Pool()
    used = []  # attribute names with resource IDs come first, in resource-map order

    def walk(node):
        for a, _, _ in node[1]:
            if a in ATTR and a not in used:
                used.append(a)
        for c in node[2]:
            walk(c)
    walk(tree)
    for a in used:
        pool.add(a)
    pool.add("android")
    pool.add(ANDROID_NS)
    ns = pool.index[ANDROID_NS]
    NONE = 0xFFFFFFFF

    body = struct.pack("<HHIIiII", 0x0100, 16, 24, 1, -1, pool.index["android"], ns)

    def elem(node):
        nonlocal body
        tag, attrs, kids = node
        name = pool.add(tag)
        enc = []
        for a, t, v in attrs:
            a_ns = NONE if a == "package" else ns
            a_name = pool.add(a)
            raw = NONE
            if t == T_STR:
                raw = data = pool.add(v)
            elif t == T_BOOL:
                data = NONE if v else 0
            else:
                data = v
            enc.append(((a_ns != NONE), ATTR.get(a, 0),
                        struct.pack("<IIIHBBI", a_ns, a_name, raw, 8, 0, t, data)))
        enc.sort(key=lambda e: (e[0], e[1]))  # framework expects ascending attribute IDs
        payload = b"".join(e[2] for e in enc)
        body += struct.pack("<HHIIi", 0x0102, 16, 36 + len(payload), 1, -1)
        body += struct.pack("<IIHHHHHH", NONE, name, 20, 20, len(attrs), 0, 0, 0) + payload
        for k in kids:
            elem(k)
        body += struct.pack("<HHIIiII", 0x0103, 16, 24, 1, -1, NONE, name)

    elem(tree)
    body += struct.pack("<HHIIiII", 0x0101, 16, 24, 1, -1, pool.index["android"], ns)

    rmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(used)) + b"".join(struct.pack("<I", ATTR[a]) for a in used)
    content = pool.chunk() + rmap + body
    return struct.pack("<HHI", 0x0003, 8, 8 + len(content)) + content


def arsc():
    """resources.arsc for RESOURCES: one type chunk per (type, density) combination."""
    values, types, keys = Pool(), Pool(), Pool()
    order = []
    for t, n, path, _, _ in RESOURCES:
        values.add(path)
        types.add(t)
        keys.add(n)
        if t not in order:
            order.append(t)

    body = b""
    for ti, t in enumerate(order, start=1):
        names = [n for tt, n, _, _, _ in RESOURCES if tt == t]
        count = len(names)
        varies = 0x0100 if any(d for tt, _, _, d, _ in RESOURCES if tt == t) else 0  # CONFIG_DENSITY
        body += struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4 * count, ti, 0, 0, count)
        body += struct.pack("<I", varies) * count
        densities = []
        for tt, _, _, d, _ in RESOURCES:
            if tt == t and d not in densities:
                densities.append(d)
        for dens in densities:
            config = struct.pack("<I", 64) + b"\0" * 10 + struct.pack("<H", dens) + b"\0" * 48
            hdr = 20 + len(config)
            offsets, data = [], b""
            for tt, n, path, d, _ in RESOURCES:
                if tt != t:
                    continue
                if d != dens:
                    offsets.append(0xFFFFFFFF)
                    continue
                offsets.append(len(data))
                data += struct.pack("<HHI", 8, 0, keys.index[n])
                data += struct.pack("<HBBI", 8, 0, T_STR, values.index[path])
            start = hdr + 4 * count
            body += struct.pack("<HHIBBHII", 0x0201, hdr, start + len(data), ti, 0, 0, count, start)
            body += config + b"".join(struct.pack("<I", o) for o in offsets) + data

    tstr, kstr = types.chunk(), keys.chunk()
    name = PKG.encode("utf-16-le").ljust(256, b"\0")
    pkg_hdr = 288
    pkg_body = tstr + kstr + body
    pkg = struct.pack("<HHII", 0x0200, pkg_hdr, pkg_hdr + len(pkg_body), 0x7f) + name
    pkg += struct.pack("<IIIII", pkg_hdr, len(order), pkg_hdr + len(tstr), len(keys.strings), 0) + pkg_body

    content = values.chunk() + pkg
    return struct.pack("<HHII", 0x0002, 12, 12 + len(content), 1) + content


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(os.path.join(out, "res", "xml"), exist_ok=True)
    open(os.path.join(out, "AndroidManifest.xml"), "wb").write(axml(MANIFEST))
    open(os.path.join(out, ACCESSIBILITY_XML_PATH), "wb").write(axml(ACCESSIBILITY))
    open(os.path.join(out, "resources.arsc"), "wb").write(arsc())
    for _, _, path, _, src in RESOURCES:
        if src:
            os.makedirs(os.path.dirname(os.path.join(out, path)), exist_ok=True)
            with open(os.path.join(ART, src), "rb") as f:
                open(os.path.join(out, path), "wb").write(f.read())
