#!/usr/bin/env python3
"""Deterministically import the audited WeChat Monet Pro v26S4 payload.

Only the exact S4 module and Play 8.0.72/3084 APKS recorded below are accepted. The
script intentionally fails closed: archive ambiguity, metadata drift, missing resources,
or an inventory change aborts before replacing any generated payload file.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import zlib
import zipfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath


MODULE_SHA256 = "87a7e6ae6ad3cccf55071a8bf77de94301ff5ce99d060c446e2932f6c4d46447"
PLAY_APKS_SHA256 = "64121c48f76dfa01e92e0ac40c4f8df8888e0d4861dcfda5b83838f06e19fd24"
PLAY_BASE_SHA256 = "c2c21dd4616f9ed939c03826ccc930fd9636f388984596e9510f05ee0cf71074"
PLAY_RESOURCES_SHA256 = "fc84586cb214cbf86a0fdb5f9035a1289d665eccd50f56b980bcc061a42ca82a"
PLAY_BASE_GRAPH_DIGEST = "1c2955c55a9029ccc0c918801dd31ea301eaa601a287c5bb0ed709fe4e3b31eb"
CLASSIC_REPAIR_SHA256 = "c172c38d941bc89dba127fc1df5b3015dbc591cabc779639fc7b5fae5d787ed8"
AAPT2_VERSION = "36.1.0"
AAPT2_SHA256 = "012764928a2e5ad747531f669fcda1cf2ccd2d0b6eb8bdb728807c17e3cf16d0"
AAPT2_VERSION_OUTPUT = "Android Asset Packaging Tool (aapt) 2.20-14042983"
DOMESTIC_PROFILES_SHA256 = "37773198690cbc6969bbbb30fc59766a08c42b8e0f196c99294ce7c4e08973bd"

# SHA-256 over each audited decoded tree's sorted relevant resource path + file-hash
# manifest (values*/colors.xml plus drawable/layout XML), not an exact APK graph digest.
DOMESTIC_SOURCE_PROVENANCE = {
    "8.0.65": {
        "resourceFileCount": 10453,
        "resourceSnapshotSha256": "fd647afef73bdb0e029db61654c629e048df296a7fee46691ea0751a73ece47c",
    },
    "8.0.67": {
        "resourceFileCount": 10589,
        "resourceSnapshotSha256": "17dca358cbe119747319fe5a76a01beb91130735c66f2c9f03e3d00deac2e3cc",
    },
    "8.0.69": {
        "resourceFileCount": 10709,
        "resourceSnapshotSha256": "c627fe9ed6afc27d9402b69e9918ab18697623985cfc7a031eac47b99d9393e4",
    },
    "8.0.74": {
        "resourceFileCount": 10895,
        "resourceSnapshotSha256": "6e5195f4f23a5e7f477938be5b0023fe9e41c474d077a936443b1e7d4a2cf00c",
    },
    "8.0.76": {
        "resourceFileCount": 10931,
        "resourceSnapshotSha256": "ef8489e7c1e8c8a40d1ee077130eaf361d44a95af5e7cb7c6ffad3ea28ce17fd",
    },
}

OVERLAYS = {
    "MonetWeChat.apk": {
        "sha256": "ab66450d2594cf96fb3cc28273a4bbfad47ceb428a8488bf85ce4ea6fbfe402c",
        "package": "monet.com.tencent.mm",
        "priority": 1,
    },
    "MonetWeChatClassicBubble.apk": {
        "sha256": "1dd9a47555fe1cea23d25c654ab0c7f4a6456d00d4e455a49a86bed4824bd89d",
        "package": "monet.classicbubble.com.tencent.mm",
        "priority": 10,
    },
    "MonetWeChatBubblePro.apk": {
        "sha256": "9de221b45e3e596580446228cd11388dca7f83d1cf53e60c666c58de7dc5de43",
        "package": "monet.bubblepro.com.tencent.mm",
        "priority": 20,
    },
    "MonetWeChatMultiSceneCorners.apk": {
        "sha256": "a7ff0f4ad873f3d2851ecaee202825aff70cb5a84be5f0f33d4d038c1eb34e0a",
        "package": "monet.multiscenecorners.com.tencent.mm",
        "priority": 30,
    },
    "MonetWeChatSolidTab.apk": {
        "sha256": "c84687f213d22663a3b9fb5a2b77b72742aebb147b58b1dac3b99706b59244e0",
        "package": "monet.solidtab.com.tencent.mm",
        "priority": 10,
    },
}

TEMPLATE_SPECS = (
    ("template_base_api31.apk", "MonetWeChat.apk", 31, None),
    ("template_base_api34.apk", "MonetWeChat.apk", 34, None),
    ("template_classic.apk", "MonetWeChatClassicBubble.apk", 31, None),
    ("template_pro.apk", "MonetWeChatBubblePro.apk", 31, None),
    ("template_corners.apk", "MonetWeChatMultiSceneCorners.apk", 31, None),
    ("template_solid_tab.apk", "MonetWeChatSolidTab.apk", 31, None),
    (
        "template_blur_tab.apk",
        "MonetWeChatSolidTab.apk",
        31,
        ("monet.solidtab.com.tencent.mm", "monet.blurtab.com.tencent.mm"),
    ),
)

EXPECTED_TEMPLATE_NAMES = tuple(spec[0] for spec in TEMPLATE_SPECS)
MANAGED_OUTPUT_FILES = ("monet_roles.json", "monet_profiles.json", "upstream.txt")
LEGACY_OUTPUT_NAMES = ("template_api31.apk", "template_api34.apk", "monet_tables.json")
PRESERVED_OUTPUT_FILES = (
    "boot-completed.sh",
    "common.sh",
    "customize.sh",
    "service.sh",
    "update-binary",
    "updater-script",
    "tools/domestic_structural_profiles.b85",
    "tools/gen_monet_tables.py",
    "tools/sync_s4_payload.py",
    "tools/test_s4_tools.py",
)
MANAGED_OUTPUT_PATHS = frozenset(MANAGED_OUTPUT_FILES) | {
    f"templates/{name}" for name in EXPECTED_TEMPLATE_NAMES
}

EXPECTED_TARGET_COUNTS = {"color": 191, "drawable": 30, "mipmap": 1, "string": 7}

# Framework colors referenced by the exact S4 base table. Keeping this audited subset here
# avoids selecting an ambient android.jar while still retaining semantic color role names.
FRAMEWORK_COLORS = {
    "0x01060033": "system_neutral2_700",
    "0x0106003a": "system_accent1_100",
    "0x0106003c": "system_accent1_300",
    "0x0106003d": "system_accent1_400",
    "0x01060040": "system_accent1_700",
    "0x01060041": "system_accent1_800",
    "0x01060047": "system_accent2_100",
    "0x0106004c": "system_accent2_600",
    "0x01060060": "system_primary_light",
    "0x0106006c": "system_surface_light",
    "0x01060070": "system_surface_container_light",
    "0x0106008b": "system_primary_dark",
    "0x01060097": "system_surface_dark",
    "0x0106009b": "system_surface_container_dark",
}

AUXILIARY_ROLES = {
    "layout/v0": {
        "id": "chat.input.container",
        "type": "layout",
        "core": True,
        "minSdk": 31,
        "xmlShapeSha256": "7d950b91ad7599fb86ade3c0f470f19853f300990a889059c519fc9468c319d4",
    },
    "style/a56": {
        "id": "payment.keyboard.key.style",
        "type": "style",
        "core": True,
        "minSdk": 31,
        "defaultValueStructure": (
            "complex:parent:-:item:16842901=literal:DIMENSION:5633:"
            "item:16842904=reference:REFERENCE:color:reference:REFERENCE:color:"
            "literal:COLOR_ARGB8:3858759680:literal:COLOR_ARGB8:3439329279:-:"
            "item:16842927=literal:HEX:17:item:16842964=reference:REFERENCE:drawable:"
            "file:-:item:16842981=literal:BOOLEAN:4294967295:"
            "item:16842996=literal:DIMENSION:1:item:16842997=literal:DIMENSION:12289:"
            "item:16843000=literal:DIMENSION:2049:item:16843001=literal:DIMENSION:2049"
        ),
    },
}

INCOMING_ROLE_IDS = {
    "drawable/bw7": ["chat.input.container"],
    "drawable/cf8": ["chat.input.container"],
    "drawable/dq_": ["payment.keyboard.key.style"],
}

KNOWN_ROLE_IDS = {
    "drawable/a36": "chat.bubble.incoming.link.mask",
    "drawable/a4m": "chat.bubble.outgoing.link.mask",
    "drawable/a53": "chat.bubble.incoming.normal",
    "drawable/a54": "chat.bubble.incoming.link",
    "drawable/a7s": "chat.voice-to-text.background",
    "drawable/a8m": "chat.bubble.outgoing.normal",
    "drawable/a8o": "chat.bubble.outgoing.link",
    "drawable/bm4": "chat.brand-action.background",
    "drawable/bw7": "chat.input.background",
    "drawable/c2c_chatfrom_remittance_expired_bg": "chat.transfer.incoming.expired",
    "drawable/c2c_chatto_remittance_expired_bg": "chat.transfer.outgoing.expired",
    "drawable/c2creceivermsgnodebg": "chat.bubble.incoming.pro",
    "drawable/c2creceivermsgnodebg_handled": "chat.bubble.incoming.pro.handled",
    "drawable/c2csendermsgnodebg": "chat.bubble.outgoing.pro",
    "drawable/c2csendermsgnodebg_handled": "chat.bubble.outgoing.pro.handled",
    "drawable/cbr": "main.surface.header.primary",
    "drawable/cbs": "main.surface.header.secondary",
    "drawable/cf8": "chat.quote.background",
    "drawable/dhq": "launcher.splash.background",
    "drawable/dik": "brand.circular.background",
    "drawable/dq7": "payment.key.primary",
    "drawable/dq8": "payment.key.secondary",
    "drawable/dq_": "payment.key.pressed",
    "drawable/p5": "chat.input.transparent-layer",
    "drawable/redcoverreceivermsgnodebg": "chat.red-envelope.incoming.alias",
    "drawable/redcoversendermsgnodebg": "chat.red-envelope.outgoing.alias",
    "drawable/yw": "chat.red-envelope.incoming.normal",
    "drawable/z1": "chat.red-envelope.incoming.received",
    "drawable/z8": "chat.red-envelope.outgoing.normal",
    "drawable/zc": "chat.red-envelope.outgoing.received",
    "mipmap/b": "launcher.themed.icon",
    "string/a0c": "about.slogan",
    "string/aua": "about.compatibility",
    "string/jva": "about.separator",
    "string/jw": "about.update-date",
    "string/kco": "about.title",
    "string/mei": "about.authors.suffix",
    "string/mej": "about.authors.prefix",
}

# Audited Play 3084 live signatures and one-shape constraints, compressed only to keep
# this importer reviewable. The decoded object is keyed by "type/name". It was produced
# from MonetApkResourceGraphLoader over the exact APKS hash above; the production graph
# digest independently protects exact-profile selection.
PLAY_CONSTRAINTS_B85 = r"""c-rM$TW?!868<lK?l$D`uD7&EgGIY3;O=g*PXe#9tg9v2vQzZG@5u3mN0xj@9Z6eY6Cg$q4d>%<F5e8P{BhxD<JscF55M<G|KoUmW$~x!e;E$0R{ej>LcHkxaS>v&x8v2n?f4e|elZ@d;=+!5KmPj5ub2Igm!E&oJ;s?~U_hCRKfl|(=I>W1cjPsvk2oit*QA8BF7PPN!EhrL7;*94#iRD;4=-Yhi#YYMclqh3PnVy*{P?N&xPEWvGRkPi4H3P+HGr^ecZFM`j3~~uP;490PQ{uacrIi^tro@G2f;QAl<A_3Ysz^;mnk<j10khShno7EeE>0NN`P&d+K~Rw)kqo(uyS#w83EeaXBg2!((R3~nt0uY)L2QCM*-)=h^{+&cE+t8{qXsqh}>Snl&<g`jN2<fF$PU>8v@w&3aoquMh!t%Eek9zhy<dy)tOy!TzMwXp3ytpJ5S=~o9*c%Z12f)dxyK~34DHGr=i$ewM2hqa!b4@WInd9(f{$|<)?~;z!23|4_ehiIdn}81w~k?Mj;ePvO{CtMvtUcm2cD#!WGp}suAr<K=064x6$LM)iu8OWUC^DFX!+^VXfmHL#wF@IZSOJ3l$Q^G-&-+VeK}Lp|!9ezb<360%mqEQyF*ftVJsz0T`pCD7AwW+;Fm6Y2^lvrkPc2S5N?IijSS*he7XFTDje0X*M<f_GGj&ozDldOJ}Wf9z*fZhgbezHd;R~Vw@K9Of<RRZSJ@i@vFkRj8LTYW1-s0L+Y%1Ix1)JhHhHqGV4l?Wreb)s-K3_5Eqqfjv8ZVp&M?dAKECfPi<|1>8`uZm5NOK@m(7q;6xp@UdB+~&kDoQNDbjC6YXB1BB7d1i^4fAJXBPN6hm&StK^*>b^hy1|Jx+*WrC(V3<bZfcEE_|WrhF5@p!hbrA~%vOh2lyd}8axxlnSC*2)9usM?a79lFwR9I@A4en=mz`~H41&IIV7s-Hh>?#IdQf2NWn{nxTF7qoO<(GhAiK~9z}y)Tt!5{QyCHy`lo>02f&4j-3U(1%aTEL04tu^9o)RW98xwU_{-xn_+`4+@1}4?CBhcb)kc4%jU$3UMNvWR!Afz+9H6F-0Z%g~_GYT}Qfm=AB#_#YnE{{zRJIL>YsMgTopjAs3d#TuvE=2iXv|$KB)8!3khM1<Rv~lZxVn4hIdK{H{=13n3Zu<J?>M*YaM1fufMz?^s!Wg+Ng+Z$zZ@<=0R~AS26RE82ZH{M=x5pJ5{_CPb4)ZBeO1bx_5*?xpT!(t1^#DY?QdD|sGpx*~~CN{!Be8{Xkuo_t&*oz}o5@0Id5+xHy`Vt>%ZT>Wu}Lf&6@V6E2jzr+Pl2FO*r&S5vp)`qibx~brZ+xLRjE{fADx&~u9r%R4@a4k0|){J>sDU*k>yDPs7l}b^PyJaxN@x+KClC!*;g~j1rm`!l_9j;B~WPu>n(2WwF`bRBD*}~~uEM#!q($U>)b##My=VYHHpgj|(r#w^0#)ZJHBTvtRo9-B&3-tRhP7X~lSLyJFDfVjA^?gk#%<HR@wC`JGxp(l(s@@UaWZCP<31<{c3$28anzRs`2Bguv1GO2gIwaH4x<mPyEGh<=Z*Wg}sWb*AHu}6#H)o}+iYf#xfzrGH&Dm*M+fDpYp4(_%I<usk{)uDrmP_Brznrfw=O<xhJLXWU(f>g<T0N9UFGEMn@3HLd<TMQVZHZmGico*kF|OyQsbFt$-d}YX%srI$)Ea;wHPo4~`(O0Lj;;S{`N8$eC(lo;?^V_vKD7>|qZnkxAdwhD(R(OnlokYiI;%ayiVK+C&xBiO@QbGF^J%*4?!UP|l$dshLtn4oZ%<~;?&SLG;p}Bpk<iL}uEz&35?lOiRySg$If*I3c;glDh!@ibC5XfnlI7izDxW-ICm;wZdQ$P)4ZUhgC(J`H%m{k#-$LbiXf)_>i8Z<^f4~D2@JA+lUGW6r>4o^Ka7`mXl{8uRHa?p}PwHuZVOrYG>XY^17emvtp`4P#r|DCAc+H!N+TJ2JPsJ6nAjp=oG(GK?Ptl|{Kr`o=y6P`byvh#C4UJ@(<z#s}tGJ2+rSg7pdUJS-Jv&)gZr@7JZi{EKn7*0L>&)!#Ot;GOvzeo^cYeP~{M!k>vUc-uc-px6dbsSOzCaZ|wB!WaqAYHf=hH+hFH<w}vK(|6V=c?euEy+5O#Z@cS$=b*C{vAR)vFVgH#80cRhey9_6S!1&(AG;E$=G}Uyx8ib4IdSRfL1WUs*9*-BqW}0z>X9xvTYw#vt3N?^YgR8u8NnXDRE}ooM*C-H+bRPxqbTliUpT3h!9%isuvxxWJ&up8?%X4*z1o-K^`En+wzFy)B)hNiNXKWM-Xvcf^~#O1bCfh`GPtk2}2GxL;H>FM{&i|L@m_Ul`s$96oQj|JvbE(nE*03=hynF|TKpufn0_N?bk<ox;MdtsBQZZw_B%h97Rkqs(^~_~&m|b{_E$JSO}QBf?W=0fe<m$HY95mUBkAl_GG(z4gk;h=HG?<3V!Ah48?^%cx~);^31O9P9Jo5bS*%3%(DBcEN5mvJfLnLE+&fKsA&oVo6L=#F#`dPD8XrC*zUKBxN}9DSAz}aPSr!d(Xw`L{V*`G$aR(8mqnY%yQI<mI}CiENB+AK^6wq5oIG$-eaz@S_Id`p_cFCSWfogP)Qm`V<Zl#MsipIl8P<>bsUiN;0}{uHd;-X^3fQ{4MeV^2tolk(Zr!!{9~o}`iEefb(x2NDkEkJVS@;mX@Udb#w%o%5Qt(y5FR}h9BqN02$6Zm*%ppj3l6gnhbIONjTWZFdSJ}!NRtc{O@t6UCtP~)=$L3WBVyo!(u73Q2Zgq?(7@p)E&gE_t9?L1q6A&QbFNZy7%$pzrL>b;CvH>nmRa;kBZ&~`KME0gY<-!P3Mqh$O(1L40_0(^4~X<JM8V{yBS=vu9b5tyFg85aJ@n9o(d(F?z#;;a2!N23l=8t#PgMg5+OF@f>?$p0ll~%3hO3puirJ6%^Wh?f{>~R>8P3_>fHHw`CuCH_Q6jJ=W8^}_!tBs@Q~)h31{x?Sta5-E1SFuxMeRvo){>ntSF?ja?)9!{lJXWkDq#(E)-(LQ`5KbPLyRfOC^biq(kz4|vH!v%kMOhwYVe<#-v+seJ`V5VVzL}eXCbx%^LyQ%=^)Xs2`Ab)q$W`$&#jg*KK}PWHbv?K0*aU;Xu#;h3=j+k0VdP*?Q#9P!1`Bq8pbiS01SJ*5VHcFAw&A+@H8o*gkZW<j}$6t>AelfBRYi~Sg>Th7G6cAlS({7*ao<an{O+Y+AZ#bB`^+9n+XBK(n>0#A<h#dg$d25F>@eLLxKWKWguB%o3x1uUMfK_y|%zAv|8HhZ<sQ<4;qh7N~^6y$MZ^ig&|=9ldC0$1}80H_`w*l)>vZ`7wCHd4c>(37VqP5N`7y?pHBx{V#_u$wM#7N7BOS@7B%HGhP20=Bpo&;4vUz^6lopR9ugiFwgN10NO4ot2(?M@&2gC^u?jZHM!0&M#=Z?15Ge>Wpo0j~A{^sM44XUkKq7c0$%gw;$`f}KYZz0Od&&*Ribieo=)-W-vfI18yN%j1ZLkAL=yVkGiM5m@LyXZ;*$r1`G#W>wLr0`WB}*`3IFb*`sE^G`5pG&4g?qT#2gD|g<Q}V!jc5rZa@t^k0%lz_I0e+JHj1rBD#fv;Mg^O&jxp*)?6>AI1JZW>55v9Z9~4>xHq{4$QQ(nEYOIo6Vjcq%gtW#m=~6U2DuzXYYb7+paz-iIZwpAj4aoIAAT$7$Lo7)$Vq^i<@t6YEa2t_#VU`sVi6}-WelBQacgSs`4K^l2lSh8s05X5PerdGGFDOPImTw{mOO>Bt4z4T`ZV=8wTA$%nWnwpoXJKv+>z0%YXQ`xLX$uT?mU{WMB@}1!EcNlLZ-JpsSqCS>`NYm2A70_#maD~ZI{1J^=ABW%Z~wgf`<Kr>MhHA6w`ZYS+b%>E2a^e1-xUZwKETg~)&(+#8<xq8{Hy?-AuJb~#9^6`YzSSK31JWM=YIh$?La3"""

RESOURCE_RE = re.compile(r"^    resource 0x[0-9a-f]+ ([^/\s]+)/([^\s]+)")
VALUE_RE = re.compile(r"^      \(([^)]*)\) (.+)$")
FILE_RE = re.compile(r"\(file\) ([^\s]+)")
BADGING_PACKAGE_RE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
BADGING_OVERLAY_RE = re.compile(r"overlay: targetPackage='([^']+)' priority='([^']+)' isStatic='([^']+)'")
BADGING_MIN_RE = re.compile(r"minSdkVersion:'([^']+)'")
BADGING_TARGET_RE = re.compile(r"targetSdkVersion:'([^']+)'")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_archive(archive: zipfile.ZipFile, label: str) -> list[str]:
    names = [entry.filename for entry in archive.infolist()]
    duplicates = sorted(name for name, count in Counter(names).items() if count != 1)
    if duplicates:
        raise ValueError(f"{label} contains duplicate entries: {duplicates}")
    for name in names:
        path = PurePosixPath(name)
        if not name or "\\" in name or path.is_absolute() or ".." in path.parts:
            raise ValueError(f"{label} contains unsafe path: {name!r}")
    return names


def find_aapt2() -> Path:
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    roots.extend([str(Path.home() / "android-sdk"), str(Path.home() / "Android/Sdk")])
    candidates = {
        Path(root).resolve() / "build-tools" / AAPT2_VERSION / "aapt2"
        for root in filter(None, roots)
    }
    for candidate in sorted(candidates):
        if not candidate.is_file():
            continue
        if sha256_file(candidate) != AAPT2_SHA256:
            raise ValueError(f"pinned aapt2 hash mismatch: {candidate}")
        version = subprocess.run(
            [str(candidate), "version"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        ).stdout.strip()
        if version != AAPT2_VERSION_OUTPUT:
            raise ValueError(f"pinned aapt2 version drift: {version!r}")
        return candidate
    raise FileNotFoundError(
        f"pinned aapt2 {AAPT2_VERSION} not found under ANDROID_HOME/ANDROID_SDK_ROOT"
    )


def aapt2_output(aapt2: Path, *args: str | Path) -> str:
    command = [str(aapt2), *map(str, args)]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        raise ValueError(f"aapt2 failed ({' '.join(command)}):\n{result.stdout}")
    return result.stdout


def parse_resources(text: str) -> dict[str, dict[str, str]]:
    resources: dict[str, dict[str, str]] = {}
    current: str | None = None
    for line in text.splitlines():
        match = RESOURCE_RE.match(line)
        if match:
            current = f"{match.group(1)}/{match.group(2)}"
            resources.setdefault(current, {})
            continue
        match = VALUE_RE.match(line)
        if match and current is not None:
            resources[current][match.group(1)] = match.group(2)
    return resources


def parse_badging(aapt2: Path, apk: Path) -> dict[str, object]:
    text = aapt2_output(aapt2, "dump", "badging", apk)
    package = BADGING_PACKAGE_RE.search(text)
    overlay = BADGING_OVERLAY_RE.search(text)
    min_sdk = BADGING_MIN_RE.search(text)
    target_sdk = BADGING_TARGET_RE.search(text)
    if not all((package, overlay, min_sdk, target_sdk)):
        raise ValueError(f"incomplete aapt2 badging for {apk}")
    assert package and overlay and min_sdk and target_sdk
    return {
        "package": package.group(1),
        "versionCode": package.group(2),
        "versionName": package.group(3),
        "targetPackage": overlay.group(1),
        "priority": int(overlay.group(2)),
        "isStatic": overlay.group(3) == "true",
        "minSdk": int(min_sdk.group(1)),
        "targetSdk": int(target_sdk.group(1)),
    }


def validate_metadata(metadata: dict[str, object], expected: dict[str, object], label: str) -> None:
    required = {
        "package": expected["package"],
        "targetPackage": "com.tencent.mm",
        "priority": expected["priority"],
        "isStatic": True,
        "versionCode": "1",
        "versionName": "1.0",
        "targetSdk": 36,
    }
    drift = {key: (metadata.get(key), value) for key, value in required.items() if metadata.get(key) != value}
    if drift:
        raise ValueError(f"{label} metadata drift: {drift}")


def decode_length8(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7f) << 8) | data[offset + 1], offset + 2
    return first, offset + 1


def decode_length16(data: bytes, offset: int) -> tuple[int, int]:
    first = struct.unpack_from("<H", data, offset)[0]
    if first & 0x8000:
        second = struct.unpack_from("<H", data, offset + 2)[0]
        return ((first & 0x7fff) << 16) | second, offset + 4
    return first, offset + 2


def encode_length8(value: int) -> bytes:
    if value > 0x7fff:
        raise ValueError("UTF-8 string pool length exceeds Android limit")
    return bytes(((value >> 8) | 0x80, value & 0xff)) if value > 0x7f else bytes((value,))


def encode_length16(value: int) -> bytes:
    if value > 0x7fffffff:
        raise ValueError("UTF-16 string pool length exceeds Android limit")
    if value > 0x7fff:
        return struct.pack("<HH", (value >> 16) | 0x8000, value & 0xffff)
    return struct.pack("<H", value)


def replace_axml_string(data: bytes, old: str, new: str) -> bytes:
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise ValueError("AndroidManifest.xml is not binary XML")
    pool_offset = 8
    chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pool_offset)
    if chunk_type != 0x0001 or header_size != 28:
        raise ValueError("binary XML does not start with a standard string pool")
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, pool_offset + 8
    )
    if style_count != 0:
        raise ValueError("styled manifest string pools are not accepted")
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{string_count}I", data, pool_offset + header_size)
    strings: list[str] = []
    for relative in offsets:
        cursor = pool_offset + strings_start + relative
        if utf8:
            _, cursor = decode_length8(data, cursor)
            byte_length, cursor = decode_length8(data, cursor)
            strings.append(data[cursor : cursor + byte_length].decode("utf-8"))
        else:
            char_length, cursor = decode_length16(data, cursor)
            strings.append(data[cursor : cursor + char_length * 2].decode("utf-16le"))
    if strings.count(old) != 1:
        raise ValueError(f"manifest package string occurrence count is {strings.count(old)}, expected 1")
    strings[strings.index(old)] = new
    encoded = bytearray()
    new_offsets: list[int] = []
    for value in strings:
        new_offsets.append(len(encoded))
        if utf8:
            raw = value.encode("utf-8")
            encoded.extend(encode_length8(len(value.encode("utf-16le")) // 2))
            encoded.extend(encode_length8(len(raw)))
            encoded.extend(raw)
            encoded.append(0)
        else:
            raw = value.encode("utf-16le")
            encoded.extend(encode_length16(len(raw) // 2))
            encoded.extend(raw)
            encoded.extend(b"\0\0")
    encoded.extend(b"\0" * ((-len(encoded)) % 4))
    prefix = bytearray(data[pool_offset : pool_offset + strings_start])
    for index, relative in enumerate(new_offsets):
        struct.pack_into("<I", prefix, header_size + index * 4, relative)
    rebuilt_pool = prefix + encoded
    struct.pack_into("<I", rebuilt_pool, 4, len(rebuilt_pool))
    rebuilt = bytearray(data[:pool_offset]) + rebuilt_pool + data[pool_offset + chunk_size :]
    struct.pack_into("<I", rebuilt, 4, len(rebuilt))
    return bytes(rebuilt)


def patch_manifest_min_sdk(data: bytes, min_sdk: int) -> bytes:
    mutable = bytearray(data)
    offset = 8
    patched = 0
    while offset < len(mutable):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", mutable, offset)
        if chunk_size < header_size or offset + chunk_size > len(mutable):
            raise ValueError("malformed binary XML chunk")
        if chunk_type == 0x0102:
            attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", mutable, offset + 24)
            cursor = offset + 16 + attribute_start
            for _ in range(attribute_count):
                if attribute_size < 20:
                    raise ValueError("malformed binary XML attribute")
                value_type = mutable[cursor + 15]
                resource_id = struct.unpack_from("<I", mutable, cursor + 4)[0]
                # The attribute name index is not a resource ID. The resource map gives its ID,
                # but minSdkVersion is the only integer value 21/31/34 under uses-sdk in these
                # audited manifests. Match its typed value plus the framework name resource ID
                # through the resource-map chunk below.
                if value_type in (0x10, 0x11):
                    value = struct.unpack_from("<I", mutable, cursor + 16)[0]
                    if value in (21, 31, 34) and resource_id != 0xFFFFFFFF:
                        # Resolve the name index through the resource map.
                        name_index = resource_id
                        map_offset = 8 + struct.unpack_from("<I", mutable, 8 + 4)[0]
                        if struct.unpack_from("<H", mutable, map_offset)[0] == 0x0180:
                            map_size = struct.unpack_from("<I", mutable, map_offset + 4)[0]
                            count = (map_size - 8) // 4
                            if name_index < count:
                                name_resource = struct.unpack_from("<I", mutable, map_offset + 8 + name_index * 4)[0]
                                if name_resource == 0x0101020C:
                                    struct.pack_into("<I", mutable, cursor + 16, min_sdk)
                                    patched += 1
                cursor += attribute_size
        offset += chunk_size
    if patched != 1:
        raise ValueError(f"patched {patched} minSdkVersion attributes, expected 1")
    return bytes(mutable)


def replace_arsc_package(data: bytes, old: str, new: str) -> bytes:
    mutable = bytearray(data)
    offset = struct.unpack_from("<H", mutable, 2)[0]
    replaced = 0
    while offset < len(mutable):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", mutable, offset)
        if chunk_size < header_size or offset + chunk_size > len(mutable):
            raise ValueError("malformed resources.arsc chunk")
        if chunk_type == 0x0200:
            name_start = offset + 12
            raw_name = bytes(mutable[name_start : name_start + 256])
            package_name = raw_name.decode("utf-16le").split("\0", 1)[0]
            if package_name == old:
                encoded = new.encode("utf-16le")
                if len(encoded) > 254:
                    raise ValueError("replacement package is too long")
                mutable[name_start : name_start + 256] = encoded + b"\0" * (256 - len(encoded))
                replaced += 1
        offset += chunk_size
    if replaced != 1:
        raise ValueError(f"replaced {replaced} ARSC package names, expected 1")
    return bytes(mutable)


def read_apk_entries(path: Path) -> dict[str, tuple[bytes, int]]:
    with zipfile.ZipFile(path) as archive:
        names = validate_archive(archive, path.name)
        result = {}
        for name in names:
            if name.endswith("/") or name.upper().startswith("META-INF/"):
                continue
            info = archive.getinfo(name)
            result[name] = (archive.read(info), info.compress_type)
        return result


def write_normalized_apk(path: Path, entries: dict[str, tuple[bytes, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", allowZip64=True) as archive:
        archive.comment = b""
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.flag_bits = 0x800
            # Stored entries make normalized bytes independent of the host Python/zlib build.
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, entries[name][0], compress_type=zipfile.ZIP_STORED)


def validate_no_dangling_files(aapt2: Path, apk: Path) -> None:
    resources = aapt2_output(aapt2, "dump", "resources", apk)
    referenced = set(FILE_RE.findall(resources))
    with zipfile.ZipFile(apk) as archive:
        names = set(validate_archive(archive, apk.name))
    missing = sorted(referenced - names)
    if missing:
        raise ValueError(f"{apk.name} has dangling resource files: {missing}")


def normalized_template(
    source: Path,
    destination: Path,
    min_sdk: int,
    package_change: tuple[str, str] | None,
    classic_repair: tuple[bytes, int] | None,
) -> None:
    entries = read_apk_entries(source)
    if classic_repair is not None:
        repair_path = "res/drawable/chat_voice_to_text.xml"
        if repair_path in entries:
            raise ValueError("Classic repair path unexpectedly already exists")
        entries[repair_path] = classic_repair
    manifest = entries["AndroidManifest.xml"][0]
    arsc = entries["resources.arsc"][0]
    if package_change is not None:
        manifest = replace_axml_string(manifest, *package_change)
        arsc = replace_arsc_package(arsc, *package_change)
    manifest = patch_manifest_min_sdk(manifest, min_sdk)
    entries["AndroidManifest.xml"] = (manifest, entries["AndroidManifest.xml"][1])
    entries["resources.arsc"] = (arsc, entries["resources.arsc"][1])
    write_normalized_apk(destination, entries)


def load_constraints() -> dict[str, dict[str, str]]:
    raw = zlib.decompress(base64.b85decode(PLAY_CONSTRAINTS_B85.encode("ascii")))
    return json.loads(raw.decode("utf-8"))


def load_domestic_profiles() -> list[dict[str, object]]:
    encoded = Path(__file__).with_name("domestic_structural_profiles.b85").read_text(
        encoding="ascii"
    ).strip()
    raw = zlib.decompress(base64.b85decode(encoded.encode("ascii")))
    if sha256_bytes(raw) != DOMESTIC_PROFILES_SHA256:
        raise ValueError("repo-owned domestic structural profile evidence hash mismatch")
    profiles = json.loads(raw.decode("utf-8"))
    version_names = {profile.get("versionName") for profile in profiles}
    if (
        len(profiles) != 5
        or version_names != set(DOMESTIC_SOURCE_PROVENANCE)
        or any(profile.get("channel") != "domestic" for profile in profiles)
        or any(profile.get("selectable") is not False for profile in profiles)
        or any("resourceDigest" in profile for profile in profiles)
    ):
        raise ValueError("domestic structural evidence must remain non-selectable and digestless")
    return [
        {
            **profile,
            "sourceEvidence": DOMESTIC_SOURCE_PROVENANCE[profile["versionName"]],
        }
        for profile in profiles
    ]


def slug(value: str) -> str:
    value = value.lower().replace("@", "").replace("0x", "")
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value or "unspecified"


def color_role_ids(
    color_keys: list[str],
    base_values: dict[str, dict[str, str]],
    framework_colors: dict[str, str],
) -> dict[str, str]:
    def semantic_value(key: str, qualifiers: str, expanding: set[str]) -> str:
        if key in expanding:
            return "cycle"
        raw = base_values.get(key, {}).get(qualifiers)
        if raw is None and qualifiers:
            raw = base_values.get(key, {}).get("")
        if raw is None:
            return "unknown"
        if raw.startswith("@0x"):
            return framework_colors.get(raw.removeprefix("@").lower(), raw)
        if raw.startswith("@android:color/"):
            return raw.removeprefix("@android:color/")
        if raw.startswith("@color/"):
            referenced = "color/" + raw.removeprefix("@color/")
            return semantic_value(referenced, qualifiers, expanding | {key})
        return raw

    semantic_groups: dict[str, list[str]] = defaultdict(list)
    for key in color_keys:
        light = semantic_value(key, "", set())
        night = semantic_value(key, "night", set())
        semantic_groups[f"{slug(light)}--{slug(night)}"].append(key)
    result = {}
    for semantic, keys in sorted(semantic_groups.items()):
        ordered = sorted(keys)
        for index, key in enumerate(ordered, 1):
            suffix = f".slot-{index:02d}" if len(ordered) > 1 else ""
            result[key] = f"theme.color.{semantic}{suffix}"
    # The tab background is a named cross-template semantic role, not a palette slot.
    if "color/df" in result:
        result["color/df"] = "main.tab.background"
    return result


def json_resource(key: str) -> dict[str, str]:
    resource_type, name = key.split("/", 1)
    return {"type": resource_type, "name": name}


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def validate_output_tree(root: Path, expected_files: set[str] | frozenset[str]) -> None:
    actual_files = set()
    actual_directories = set()
    for directory, directory_names, file_names in os.walk(root, followlinks=False):
        directory_path = Path(directory)
        for name in directory_names:
            path = directory_path / name
            if path.is_symlink():
                raise ValueError(f"payload output contains a symlink: {path.relative_to(root)}")
            actual_directories.add(path.relative_to(root).as_posix())
        for name in file_names:
            path = directory_path / name
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"payload output contains a non-regular file: {path.relative_to(root)}")
            actual_files.add(path.relative_to(root).as_posix())
    expected_directories = {
        parent.as_posix()
        for name in expected_files
        for parent in PurePosixPath(name).parents
        if parent.as_posix() != "."
    }
    if actual_files != set(expected_files) or actual_directories != expected_directories:
        raise ValueError(
            "payload output inventory drift: "
            f"files={sorted(actual_files)}, directories={sorted(actual_directories)}"
        )


def copy_preserved_output_files(output: Path, candidate: Path) -> None:
    for relative in PRESERVED_OUTPUT_FILES:
        source = output
        for component in PurePosixPath(relative).parts:
            source = source / component
            if source.is_symlink():
                raise ValueError(f"required preserved payload asset is a symlink: {relative}")
        if not source.is_file():
            raise ValueError(f"required preserved payload asset is missing or non-regular: {relative}")
        destination = candidate / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


def validate_generated_output(generated: Path) -> None:
    validate_output_tree(generated, MANAGED_OUTPUT_PATHS)


def publish_generated(generated: Path, output: Path) -> None:
    """Publish one coherent payload root while retaining non-managed module files."""
    validate_generated_output(generated)
    output.parent.mkdir(parents=True, exist_ok=True)
    candidate = Path(tempfile.mkdtemp(prefix=f".{output.name}.publish-", dir=output.parent))
    try:
        candidate_mode = 0o755
        if output.exists():
            if not output.is_dir() or output.is_symlink():
                raise ValueError(f"payload output is not a real directory: {output}")
            candidate_mode = output.stat().st_mode & 0o777
            copy_preserved_output_files(output, candidate)
        candidate.chmod(candidate_mode)
        shutil.copytree(generated / "templates", candidate / "templates")
        for name in MANAGED_OUTPUT_FILES:
            shutil.copyfile(generated / name, candidate / name)
        expected_files = MANAGED_OUTPUT_PATHS
        if output.exists():
            expected_files = expected_files | set(PRESERVED_OUTPUT_FILES)
        validate_output_tree(candidate, expected_files)

        if output.exists():
            backup = Path(tempfile.mkdtemp(prefix=f".{output.name}.backup-", dir=output.parent))
            backup.rmdir()
            os.replace(output, backup)
            try:
                os.replace(candidate, output)
            except Exception as install_error:
                try:
                    os.replace(backup, output)
                except Exception as rollback_error:
                    raise RuntimeError(
                        f"payload install and rollback failed; intact backup remains at {backup}"
                    ) from rollback_error
                raise install_error
            try:
                shutil.rmtree(backup)
            except Exception as cleanup_error:
                raise RuntimeError(
                    f"payload installed but old backup cleanup failed; backup remains at {backup}"
                ) from cleanup_error
        else:
            os.replace(candidate, output)
    finally:
        if candidate.exists():
            shutil.rmtree(candidate)


def build_catalog(
    source_resources: dict[str, dict[str, dict[str, str]]],
    play_resources: dict[str, dict[str, str]],
    framework_colors: dict[str, str],
) -> tuple[dict[str, object], dict[str, str]]:
    play_keys = set(play_resources)
    binding_keys = {name: sorted(set(resources) & play_keys) for name, resources in source_resources.items()}
    target_keys = sorted(set().union(*map(set, binding_keys.values())))
    counts = Counter(key.split("/", 1)[0] for key in target_keys)
    if dict(sorted(counts.items())) != EXPECTED_TARGET_COUNTS:
        raise ValueError(f"S4/Play target inventory drift: {dict(sorted(counts.items()))}")
    constraints = load_constraints()
    if set(constraints) != set(target_keys):
        raise ValueError("embedded Play constraint inventory does not match the live exact APKS")

    target_role_ids = dict(KNOWN_ROLE_IDS)
    target_role_ids.update(
        color_role_ids(
            [key for key in target_keys if key.startswith("color/")],
            source_resources["MonetWeChat.apk"],
            framework_colors,
        )
    )
    if set(target_role_ids) != set(target_keys):
        missing = sorted(set(target_keys) - set(target_role_ids))
        raise ValueError(f"missing semantic roles: {missing}")
    if not set(AUXILIARY_ROLES).issubset(play_keys):
        raise ValueError("audited auxiliary layout/style resources are absent from Play 3084")
    role_ids = {
        **target_role_ids,
        **{key: definition["id"] for key, definition in AUXILIARY_ROLES.items()},
    }
    if len(set(role_ids.values())) != len(role_ids):
        raise ValueError("semantic role IDs are not unique")

    roles = []
    for key in target_keys:
        resource_type = key.split("/", 1)[0]
        constraint = dict(constraints[key])
        # ZIP file paths are build-local evidence, not a cross-build hard constraint.
        if resource_type != "color":
            constraint.pop("defaultValue", None)
            constraint.pop("nightValue", None)
        role = {
            "id": target_role_ids[key],
            "type": resource_type,
            "core": resource_type not in {"string", "mipmap"},
            "minSdk": 33 if resource_type == "mipmap" else 31,
            **constraint,
        }
        if key in INCOMING_ROLE_IDS:
            role["requiredIncomingRoleIds"] = INCOMING_ROLE_IDS[key]
        roles.append(role)
    roles.extend(dict(AUXILIARY_ROLES[key]) for key in sorted(AUXILIARY_ROLES))

    def bindings(source_name: str) -> dict[str, dict[str, str]]:
        return {
            target_role_ids[key]: json_resource(key)
            for key in binding_keys[source_name]
        }

    overlays = [
        {
            "id": "base-api31",
            "packageName": "monet.com.tencent.mm",
            "fileName": "MonetWeChat.apk",
            "templateFile": "templates/template_base_api31.apk",
            "templateResources": bindings("MonetWeChat.apk"),
        },
        {
            "id": "base-api34",
            "packageName": "monet.com.tencent.mm",
            "fileName": "MonetWeChat.apk",
            "templateFile": "templates/template_base_api34.apk",
            "templateResources": bindings("MonetWeChat.apk"),
        },
        {
            "id": "classic-bubble",
            "packageName": "monet.classicbubble.com.tencent.mm",
            "fileName": "MonetWeChatClassicBubble.apk",
            "templateFile": "templates/template_classic.apk",
            "selectionCondition": {"bubbleStyle": "CLASSIC"},
            "templateResources": bindings("MonetWeChatClassicBubble.apk"),
        },
        {
            "id": "pro-bubble",
            "packageName": "monet.bubblepro.com.tencent.mm",
            "fileName": "MonetWeChatBubblePro.apk",
            "templateFile": "templates/template_pro.apk",
            "selectionCondition": {"bubbleStyle": "PRO"},
            "templateResources": bindings("MonetWeChatBubblePro.apk"),
        },
        {
            "id": "multi-scene-corners",
            "packageName": "monet.multiscenecorners.com.tencent.mm",
            "fileName": "MonetWeChatMultiSceneCorners.apk",
            "templateFile": "templates/template_corners.apk",
            "selectionCondition": {"multiSceneCornersEnabled": True},
            "templateResources": bindings("MonetWeChatMultiSceneCorners.apk"),
        },
        {
            "id": "solid-tab",
            "packageName": "monet.solidtab.com.tencent.mm",
            "fileName": "MonetWeChatSolidTab.apk",
            "templateFile": "templates/template_solid_tab.apk",
            "selectionCondition": {"tabStyle": "SOLID"},
            "templateResources": bindings("MonetWeChatSolidTab.apk"),
        },
        {
            "id": "blur-tab",
            "packageName": "monet.blurtab.com.tencent.mm",
            "fileName": "MonetWeChatBlurTab.apk",
            "templateFile": "templates/template_blur_tab.apk",
            "selectionCondition": {"tabStyle": "BLUR"},
            "templateResources": bindings("MonetWeChatSolidTab.apk"),
        },
    ]
    return {"schemaVersion": 1, "roles": roles, "overlays": overlays}, role_ids


def build_profiles(role_ids: dict[str, str]) -> dict[str, object]:
    exact_roles = {role_id: json_resource(key) for key, role_id in sorted(role_ids.items())}
    return {
        "schemaVersion": 1,
        "digestAlgorithm": "monet-resource-graph-v1",
        "verifiedProfiles": [
            {
                "resourceDigest": PLAY_BASE_GRAPH_DIGEST,
                "versionName": "8.0.72",
                "versionCode": 3084,
                "channel": "google-play",
                "sourceApksSha256": PLAY_APKS_SHA256,
                "roles": exact_roles,
            }
        ],
        "structuralOnlyProfiles": [
            {
                "versionName": "8.0.72",
                "versionCode": 3085,
                "channel": "google-play",
                "selectable": False,
                "reason": "S4 declares structural compatibility, but no 3085 APK was supplied for digest verification.",
            },
            *load_domestic_profiles(),
        ],
    }


def sync(module_zip: Path, play_apks: Path, output: Path) -> None:
    if sha256_file(module_zip) != MODULE_SHA256:
        raise ValueError("module ZIP SHA-256 does not match the audited S4 input")
    if sha256_file(play_apks) != PLAY_APKS_SHA256:
        raise ValueError("Play APKS SHA-256 does not match the audited 3084 input")
    aapt2 = find_aapt2()

    with tempfile.TemporaryDirectory(prefix="wekit-s4-sync-") as temporary:
        work = Path(temporary)
        sources = work / "sources"
        sources.mkdir()
        with zipfile.ZipFile(module_zip) as archive:
            names = validate_archive(archive, "S4 module ZIP")
            apk_names = sorted(
                PurePosixPath(name).name
                for name in names
                if name.startswith("files/") and name.endswith(".apk")
            )
            if apk_names != sorted(OVERLAYS):
                raise ValueError(f"unexpected S4 overlay APK inventory: {apk_names}")
            for name, expected in OVERLAYS.items():
                data = archive.read(f"files/{name}")
                if sha256_bytes(data) != expected["sha256"]:
                    raise ValueError(f"source APK hash mismatch: {name}")
                (sources / name).write_bytes(data)

        with zipfile.ZipFile(play_apks) as archive:
            validate_archive(archive, "Play APKS")
            info = json.loads(archive.read("info.json"))
            expected_info = {"pname": "com.tencent.mm", "release_version": "8.0.72", "versioncode": "3084"}
            if {key: info.get(key) for key in expected_info} != expected_info:
                raise ValueError("Play APKS info.json metadata drift")
            play_base_data = archive.read("base.apk")
        if sha256_bytes(play_base_data) != PLAY_BASE_SHA256:
            raise ValueError("Play base.apk hash mismatch")
        play_base = work / "base.apk"
        play_base.write_bytes(play_base_data)
        with zipfile.ZipFile(play_base) as archive:
            resources_data = archive.read("resources.arsc")
        if sha256_bytes(resources_data) != PLAY_RESOURCES_SHA256:
            raise ValueError("Play resources.arsc hash mismatch")

        source_resources = {}
        for name, expected in OVERLAYS.items():
            source = sources / name
            metadata = parse_badging(aapt2, source)
            validate_metadata(metadata, expected, name)
            source_resources[name] = parse_resources(aapt2_output(aapt2, "dump", "resources", source))
        play_resources = parse_resources(aapt2_output(aapt2, "dump", "resources", play_base))
        catalog, role_ids = build_catalog(source_resources, play_resources, FRAMEWORK_COLORS)

        base_entries = read_apk_entries(sources / "MonetWeChat.apk")
        repair = base_entries.get("res/drawable/chat_voice_to_text.xml")
        if repair is None or sha256_bytes(repair[0]) != CLASSIC_REPAIR_SHA256:
            raise ValueError("S4 base Classic repair XML hash mismatch")

        generated = work / "generated"
        templates = generated / "templates"
        for template_name, source_name, min_sdk, package_change in TEMPLATE_SPECS:
            destination = templates / template_name
            normalized_template(
                sources / source_name,
                destination,
                min_sdk,
                package_change,
                repair if source_name == "MonetWeChatClassicBubble.apk" else None,
            )
            expected_package = package_change[1] if package_change else OVERLAYS[source_name]["package"]
            expected = dict(OVERLAYS[source_name])
            expected["package"] = expected_package
            metadata = parse_badging(aapt2, destination)
            validate_metadata(metadata, expected, template_name)
            if metadata["minSdk"] != min_sdk:
                raise ValueError(f"{template_name} minSdk mismatch after normalization")
            validate_no_dangling_files(aapt2, destination)

        write_json(generated / "monet_roles.json", catalog)
        write_json(generated / "monet_profiles.json", build_profiles(role_ids))
        (generated / "upstream.txt").write_text(
            "微信莫奈取色 Pro v26S4\n"
            "Common upstream authors: 枯れ木, H_1e93d, HSSkyBoy\n"
            f"Source ZIP SHA-256: {MODULE_SHA256}\n"
            "WeKit performs deterministic template normalization and runtime resource adaptation.\n",
            encoding="utf-8",
        )

        publish_generated(generated, output)

    print(f"generated 7 templates and {len(role_ids)} semantic roles in {output}")
    print(f"verified Play 3084 base graph digest: {PLAY_BASE_GRAPH_DIGEST}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module-zip", required=True, type=Path)
    parser.add_argument("--play-apks", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    sync(arguments.module_zip.resolve(), arguments.play_apks.resolve(), arguments.output.resolve())


if __name__ == "__main__":
    main()
