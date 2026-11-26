#!/usr/bin/env python3
# MessWise Urdu fonts: the same two fonts as Dr Jason Partner and MohallaCircle (Atif, 27 Nov 2026):
#
#   app/src/main/assets/fonts/mw_jameel_noori.ttf     Urdu text      from Jameel Noori Nastaleeq
#   app/src/main/assets/fonts/mw_jameel_kasheeda.ttf  Urdu headings  from Jameel Noori Nastaleeq Kasheeda
#
# Same method as MohallaCircle's tools/urdu-fonts/build.py:
#   * every shape is kept (members type their own Urdu names and items, so the fonts are not trimmed to app words);
#   * Jameel draws small, so it is enlarged 1.35x inside the font (unitsPerEm 2048 -> 1517); outlines untouched;
#   * Latin letters, digits and spaces are removed, so Android draws them with the phone's normal font (Roboto).
# Verification (build fails otherwise): every word in test-words.txt and in the app's Urdu texts (tools/strings.py)
# is shaped with HarfBuzz using the original and the built font and must give identical glyphs, advances and offsets.
#
# Run:   python3 tools/urdu-fonts/build.py /path/to/folder-with-the-two-original-.ttf.xz-files
# Needs: python3 + fontTools, libharfbuzz.so.0. The original fonts are not kept in this public repo.
# The fonts' own notice: "This Font Is Free Of Charge For Urdu Lovers" (designer "Urdu Lover", 2008/2009).
import ctypes as C, glob, io, json, lzma, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "fonts")
SRC_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "src")
SCALE = 1.35  # same enlargement Dr Jason Partner applies with CSS size-adjust
FONTS = [("JameelNooriNastaleeq.ttf.xz", "mw_jameel_noori.ttf", "MW Jameel Noori"),
         ("JameelNooriNastaleeqKasheeda.ttf.xz", "mw_jameel_kasheeda.ttf", "MW Jameel Kasheeda")]
# Arabic-script blocks, joiners, directional marks and isolates. No ASCII: Inter supplies Latin, digits and spaces.
UNICODES = "U+0600-06FF,U+0750-077F,U+08A0-08FF,U+FB50-FDFF,U+FE70-FEFF,U+200C-200F,U+2066-2069"
URDU = re.compile("[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿‌‍]+")
LETTER = re.compile("[\u0621-\u064A\u0660-\u06D3\u06D5\u06EE-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFC]")

def corpus():
    words = set(open(os.path.join(HERE, "test-words.txt"), encoding="utf8").read().split())
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import strings as app_texts
    for en, ur in app_texts.S.values():
        words.update(URDU.findall(ur))
    for forms in getattr(app_texts, "P", {}).values():
        for f in forms[1]:
            words.update(URDU.findall(f))
    # a "word" must contain at least one letter (not only joiners or byte-order marks)
    return sorted(w for w in words if LETTER.search(w))

# ---- HarfBuzz through ctypes
hb = C.CDLL("libharfbuzz.so.0")
for name, res, args in [
    ("hb_blob_create", C.c_void_p, [C.c_char_p, C.c_uint, C.c_int, C.c_void_p, C.c_void_p]),
    ("hb_face_create", C.c_void_p, [C.c_void_p, C.c_uint]), ("hb_font_create", C.c_void_p, [C.c_void_p]),
    ("hb_buffer_create", C.c_void_p, []), ("hb_buffer_clear_contents", None, [C.c_void_p]),
    ("hb_buffer_add_utf8", None, [C.c_void_p, C.c_char_p, C.c_int, C.c_uint, C.c_int]),
    ("hb_buffer_set_direction", None, [C.c_void_p, C.c_int]), ("hb_buffer_set_script", None, [C.c_void_p, C.c_uint32]),
    ("hb_language_from_string", C.c_void_p, [C.c_char_p, C.c_int]), ("hb_buffer_set_language", None, [C.c_void_p, C.c_void_p]),
    ("hb_shape", None, [C.c_void_p, C.c_void_p, C.c_void_p, C.c_uint])]:
    fn = getattr(hb, name); fn.restype = res; fn.argtypes = args
class Info(C.Structure): _fields_ = [("gid", C.c_uint32), ("mask", C.c_uint32), ("cluster", C.c_uint32), ("v1", C.c_uint32), ("v2", C.c_uint32)]
class Pos(C.Structure): _fields_ = [("xa", C.c_int32), ("ya", C.c_int32), ("xo", C.c_int32), ("yo", C.c_int32), ("v", C.c_uint32)]
hb.hb_buffer_get_glyph_infos.restype = C.POINTER(Info); hb.hb_buffer_get_glyph_infos.argtypes = [C.c_void_p, C.POINTER(C.c_uint)]
hb.hb_buffer_get_glyph_positions.restype = C.POINTER(Pos); hb.hb_buffer_get_glyph_positions.argtypes = [C.c_void_p, C.POINTER(C.c_uint)]
ARAB = (ord("A") << 24) | (ord("r") << 16) | (ord("a") << 8) | ord("b")
UR = hb.hb_language_from_string(b"ur", -1)

class Shaper:
    def __init__(self, data):
        self.data = data  # keep the bytes alive for HarfBuzz
        self.font = hb.hb_font_create(hb.hb_face_create(hb.hb_blob_create(data, len(data), 1, None, None), 0))
        self.buf = hb.hb_buffer_create()
    def shape(self, text):
        b = self.buf; hb.hb_buffer_clear_contents(b); hb.hb_buffer_add_utf8(b, text.encode(), -1, 0, -1)
        hb.hb_buffer_set_direction(b, 5); hb.hb_buffer_set_script(b, ARAB); hb.hb_buffer_set_language(b, UR)
        hb.hb_shape(self.font, b, None, 0)
        n = C.c_uint(); gi = hb.hb_buffer_get_glyph_infos(b, C.byref(n)); gp = hb.hb_buffer_get_glyph_positions(b, C.byref(n))
        return [(gi[i].gid, gp[i].xa, gp[i].xo, gp[i].yo) for i in range(n.value)]

def main():
    from fontTools.ttLib import TTFont
    from fontTools import subset
    words = corpus()
    os.makedirs(OUT_DIR, exist_ok=True)
    for src, out, family in FONTS:
        data = lzma.decompress(open(os.path.join(SRC_DIR, src), "rb").read())
        tt = TTFont(io.BytesIO(data))
        opts = subset.Options()
        opts.layout_features = ["*"]; opts.layout_closure = True; opts.name_IDs = ["*"]; opts.name_languages = ["*"]
        opts.notdef_outline = True; opts.hinting = False; opts.glyph_names = True
        opts.drop_tables += ["DSIG", "hdmx", "LTSH", "VDMX"]
        sub = subset.Subsetter(opts); sub.populate(unicodes=subset.parse_unicodes(UNICODES)); sub.subset(tt)
        upm = tt["head"].unitsPerEm
        tt["head"].unitsPerEm = round(upm / SCALE)
        for rec in tt["name"].names:  # distinct family name, original copyright/notice kept
            if rec.nameID in (1, 4, 16): rec.string = family
            elif rec.nameID == 6: rec.string = family.replace(" ", "")
        buf = io.BytesIO(); tt.save(buf); built = buf.getvalue()

        orig, new = Shaper(data), Shaper(built)
        on = TTFont(io.BytesIO(data), lazy=True).getGlyphOrder(); nn = TTFont(io.BytesIO(built), lazy=True).getGlyphOrder()
        bad = [w for w in words if [(on[g], *r) for g, *r in orig.shape(w)] != [(nn[g], *r) for g, *r in new.shape(w)]]
        if bad: sys.exit(f"{out}: {len(bad)} words shape differently from the original font, e.g. {bad[:5]}")
        # identical shaping means the built font draws everything the original draws; report what neither draws
        lacking = [w for w in words if any(g == 0 for g, *_ in orig.shape(w))]

        dest = os.path.join(OUT_DIR, out)
        open(dest, "wb").write(built)
        print(f"{out}: all {len(words)} test words identical to the original; {len(nn)} shapes; "
              f"unitsPerEm {upm} -> {tt['head'].unitsPerEm} (x{SCALE}); {os.path.getsize(dest) // 1024} KB"
              + (f"; {len(lacking)} test strings use characters the original font also lacks, e.g. {lacking[:3]}" if lacking else ""))

if __name__ == "__main__":
    main()
