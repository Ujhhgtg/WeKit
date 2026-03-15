package moe.ouom.wekit.hooks.api.net

object NativeMap {
    val NATIVE_CONFIG = mutableMapOf(
        522 to Triple("mx4.as5", 237, 1000000237),   // /cgi-bin/micromsg-bin/newsendmsg
        137 to Triple("mx4.uq6", 44, 1000000044),    // /cgi-bin/micromsg-bin/verifyuser
        120 to Triple("mx4.y3", 36, 1000000036),     // /cgi-bin/micromsg-bin/addchatroommember
        625 to Triple("mx4.lr", 9, 1000000009),      // /cgi-bin/micromsg-bin/uploadmsgimg
        222 to Triple("mx4.lr5", 107, 1000000107),   // /cgi-bin/micromsg-bin/sendappmsg
        159 to Triple("mx4.se3", 51, 1000000051),    // /cgi-bin/micromsg-bin/getpackagelist
        168 to Triple("mx4.dg3", 67, 1000000067),    // /cgi-bin/micromsg-bin/getqrcode
        175 to Triple("mx4.lm6", 68, 1000000068),    // /cgi-bin/micromsg-bin/sendemoji
        106 to Triple("mx4.dp5", 34, 1000000034),    // /cgi-bin/micromsg-bin/searchcontact
        211 to Triple("mx4.m36", 98, 1000000098),    // /cgi-bin/micromsg-bin/mmsnstimeline
        212 to Triple("mx4.w36", 99, 1000000099),    // /cgi-bin/micromsg-bin/mmsnsuserpage
        209 to Triple("mx4.t26", 97, 1000000097),    // /cgi-bin/micromsg-bin/mmsnspost
        207 to Triple("mx4.s36", 95, 1000000095),    // /cgi-bin/micromsg-bin/mmsnsupload
        214 to Triple("mx4.d36", 102, 1000000102),   // /cgi-bin/micromsg-bin/mmsnssync
        213 to Triple("mx4.z06", 100, 1000000100),   // /cgi-bin/micromsg-bin/mmsnscomment
        218 to Triple("mx4.m26", 104, 1000000104),   // /cgi-bin/micromsg-bin/mmsnsobjectop
        210 to Triple("mx4.g26", 101, 1000000101),   // /cgi-bin/micromsg-bin/mmsnsobjectdetail
        1992 to Triple("mx4.ud6", 185, 1000000185),  // /cgi-bin/mmpay-bin/gettransferwording
        1679 to Triple("mx4.ud6", 185, 1000000185),  // /cgi-bin/mmpay-bin/tenpay/getbannerinfo
        1544 to Triple("mx4.ud6", 185, 1000000185),  // /cgi-bin/mmpay-bin/transferplaceorder
        1501 to Triple("mx4.ud6", 185, 1000000185),  // /cgi-bin/mmpay-bin/tenpay/bindquerynew
        27 to Triple("mx4.on6", 19, 1000000019),     // /cgi-bin/micromsg-bin/uploadvoice

        1779 to Triple("mx4.kw", 0, 0),              // /cgi-bin/mmpay-bin/transferoldpaycheck
        28828 to Triple("mx4.qg4", 0, 0),            // /cgi-bin/mmpay-bin/sec/report_mmpaysecreport
        1554 to Triple("mx4.gr3", 0, 0),             // /cgi-bin/mmpay-bin/operationwxhb
        1575 to Triple("mx4.gr3", 0, 0),             // /cgi-bin/mmpay-bin/requestwxhb
        1581 to Triple("mx4.gr3", 0, 0),             // /cgi-bin/mmpay-bin/receivewxhb
        1585 to Triple("mx4.gr3", 0, 0),             // /cgi-bin/mmpay-bin/qrydetailwxhb
        2929 to Triple(
            "mx4.to3",
            0,
            0
        ),             // /cgi-bin/mmpay-bin/ftfhb/businesscallbackwxhb
        2715 to Triple("mx4.ti5", 0, 0),             // /cgi-bin/mmpay-bin/ftfhb/wxhbreport
        2783 to Triple("mx4.iw", 0, 0),              // /cgi-bin/mmpay-bin/beforetransfer
        17038 to Triple("mx4.a83", 0, 0),            // /cgi-bin/micromsg-bin/getcoversetreddotinfo
        21017 to Triple(
            "com.tencent.mm.plugin.luckymoney.model.j0",
            0,
            0
        ), // /cgi-bin/micromsg-bin/getdefaultredpacket

        3565 to Triple("mx4.ft2", 0, 0),             // /cgi-bin/micromsg-bin/findersync
        3930 to Triple("mx4.e81", 0, 0),             // /cgi-bin/micromsg-bin/finderinit
        3736 to Triple("mx4.fw2", 0, 0),             // /cgi-bin/micromsg-bin/finderuserpage
        9037 to Triple("mx4.xv2", 0, 0),             // /cgi-bin/micromsg-bin/finderuserpageheader
        11847 to Triple("mx4.u71", 0, 0),            // /cgi-bin/micromsg-bin/finderhomepage
        6449 to Triple(
            "mx4.ts0",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderbatchgetobjectasyncloadinfo
        3867 to Triple("mx4.e01", 0, 0),             // /cgi-bin/micromsg-bin/finderfollow
        10072 to Triple("mx4.oj5", 0, 0),            // /cgi-bin/micromsg-bin/findersdkreport
        3828 to Triple("mx4.a51", 0, 0),             // /cgi-bin/micromsg-bin/findergetmsgsessionid
        3901 to Triple("x52.a3", 0, 0),              // /cgi-bin/micromsg-bin/finderstream
        7312 to Triple("mx4.ve2", 0, 0),             // /cgi-bin/micromsg-bin/findernavlivestream
        6611 to Triple("mx4.bw2", 0, 0),             // /cgi-bin/micromsg-bin/finderuserpagepreview
        9570 to Triple(
            "mx4.oj5",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderlivetipssdkreport
        10526 to Triple(
            "mx4.ep0",
            0,
            0
        ),            // /cgi-bin/micromsg-bin/fetchfindermembershiphomeinfo
        14246 to Triple(
            "pm5.ce",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/findergetoftenreadauthor
        8560 to Triple("mx4.k11", 0, 0),             // /cgi-bin/micromsg-bin/findergetbulletcomment
        3906 to Triple("mx4.jv0", 0, 0),             // /cgi-bin/micromsg-bin/findercomment
        3710 to Triple("mx4.z91", 0, 0),             // /cgi-bin/micromsg-bin/finderlike
        3515 to Triple("mx4.iz0", 0, 0),             // /cgi-bin/micromsg-bin/finderfav
        4004 to Triple("mx4.ny0", 0, 0),             // /cgi-bin/micromsg-bin/finderenhance
        11842 to Triple("mx4.u11", 0, 0),            // /cgi-bin/micromsg-bin/findergetcommentlist
        7454 to Triple(
            "mx4.nr0",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderasyncgetcommentinfo
        19525 to Triple("mx4.tr2", 0, 0),            // /cgi-bin/micromsg-bin/finderstreamrerank
        3839 to Triple(
            "mx4.rv0",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderconsumeprefetchreport
        6658 to Triple("mx4.wt0", 0, 0),             // /cgi-bin/micromsg-bin/findercheckprefetch
        6681 to Triple("mx4.az0", 0, 0),             // /cgi-bin/micromsg-bin/finderextstatsreport
        3539 to Triple("mx4.o81", 0, 0),             // /cgi-bin/micromsg-bin/finderjoinlive
        6479 to Triple(
            "mx4.i41",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/findergetliverelatedlist
        3520 to Triple("mx4.qa2", 0, 0),             // /cgi-bin/micromsg-bin/findermarkread
        5971 to Triple(
            "mx4.y62",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderlivesyncextrainfo
        4053 to Triple(
            "mx4.m41",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/findergetliverewardgiftlist
        11231 to Triple(
            "mx4.on1",
            0,
            0
        ),            // /cgi-bin/micromsg-bin/finderlivegetfloatmsgconfig
        3976 to Triple("mx4.a41", 0, 0),             // /cgi-bin/micromsg-bin/findergetlivemsg
        6271 to Triple(
            "mx4.sp1",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderlivegetredpacketinfo
        3861 to Triple("mx4.u31", 0, 0),             // /cgi-bin/micromsg-bin/findergetliveinfo
        3776 to Triple("mx4.br2", 0, 0),             // /cgi-bin/micromsg-bin/finderstatsreport
        11815 to Triple(
            "mx4.fx1",
            0,
            0
        ),            // /cgi-bin/micromsg-bin/finderlivenavstreamstatus
        9665 to Triple(
            "mx4.f22",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/finderliverelatedliststatus
        4210 to Triple("mx4.q41", 0, 0),             // /cgi-bin/micromsg-bin/findergetlivetabs
        7289 to Triple("mx4.mw2", 0, 0),             // /cgi-bin/micromsg-bin/findernewuserprepare
        5864 to Triple("mx4.p61", 0, 0),             // /cgi-bin/micromsg-bin/findergetsvrexptconfig
        6401 to Triple("mx4.fm3", 0, 0),             // /cgi-bin/micromsg-bin/getwecoinbalance
        22006 to Triple("mx4.oj5", 0, 0),            // /cgi-bin/micromsg-bin/finderreddotsdkreport
        3763 to Triple("mx4.s11", 0, 0),             // /cgi-bin/micromsg-bin/findergetcommentdetail
        389 to Triple(
            "mx4.pn2",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/finderrefreshpreloadinfo
        14285 to Triple(
            "mx4.i92",
            0,
            0
        ),            // /cgi-bin/micromsg-bin/finderlivevoipstreamstatus
        3980 to Triple("mx4.nu0", 0, 0),             // /cgi-bin/micromsg-bin/findercollectunread

        379 to Triple("mx4.y53", 0, 0),              // /cgi-bin/micromsg-bin/getcdndns
        721 to Triple("mx4.s00", 0, 0),              // /cgi-bin/micromsg-bin/checkresupdate
        251 to Triple("mx4.r66", 0, 0),              // /cgi-bin/micromsg-bin/statusnotify
        8674 to Triple("mx4.gh5", 0, 0),             // /cgi-bin/micromsg-bin/contactsync
        6238 to Triple("mx4.md3", 0, 0),             // /cgi-bin/micromsg-bin/getnetworkinfo
        15031 to Triple("mx4.gh5", 0, 0),            // /cgi-bin/micromsg-bin/openimcontactsync
        22246 to Triple("mx4.gh5", 0, 0),            // /cgi-bin/micromsg-bin/chatroomheadimgsync
        526 to Triple("mx4.sd3", 0, 0),              // /cgi-bin/micromsg-bin/getonlineinfo
        11705 to Triple("g31.b1", 0, 0),             // /cgi-bin/micromsg-bin/roambackuppackagesget
        1047 to Triple("mx4.mt", 0, 0),              // /cgi-bin/micromsg-bin/cardsync
        849 to Triple("mx4.gs5", 0, 0),              // /cgi-bin/micromsg-bin/sendpat
        988 to Triple("mx4.j73", 0, 0),              // /cgi-bin/micromsg-bin/getkvidkeystrategy
        110 to Triple(
            "mx4.xm6",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/uploadmsgimg (注意：与625区别)
        5865 to Triple("fc3.p", 0, 0),               // /cgi-bin/micromsg-bin/mulmediareportcgi
        17911 to Triple("mx4.ef3", 0, 0),            // /cgi-bin/micromsg-bin/getpintopfavinchat
        17849 to Triple(
            "mx4.bq",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/bypinfosynctypingcontactticket
        3673 to Triple("mx4.sq", 0, 0),              // /cgi-bin/micromsg-bin/bypsync
        8116 to Triple(
            "mx4.g07",
            0,
            0
        ),             // /cgi-bin/micromsg-bin/wevisiongeteffectconfig
        10056 to Triple("mx4.i07", 0, 0),            // /cgi-bin/micromsg-bin/wevisiongetmodel
        697 to Triple("mx4.ne", 0, 0),               // /cgi-bin/micromsg-bin/mmbatchemojidownload
        771 to Triple("mx4.ki5", 0, 0),              // /cgi-bin/micromsg-bin/reportclientcheck
        996 to Triple("mx4.i20", 0, 0),              // /cgi-bin/micromsg-bin/newreportkvcomm
        986 to Triple("mx4.i20", 0, 0),              // /cgi-bin/micromsg-bin/newreportidkey
        4005 to Triple("mx4.k23", 0, 0),             // /cgi-bin/micromsg-bin/geocoderlocation
        810 to Triple("mx4.ku4", 0, 0),              // /cgi-bin/micromsg-bin/openimsync
        5171 to Triple("mx4.bf5", 0, 0),             // /cgi-bin/micromsg-bin/reddot_report
        1200 to Triple("mx4.ni6", 0, 0),             // /cgi-bin/mmbiz-bin/translatelink
        655 to Triple("mx4.l33", 0, 0),              // /cgi-bin/micromsg-bin/getaddress
        457 to Triple("mx4.oe3", 0, 0),              // /cgi-bin/micromsg-bin/getpoilist
        11363 to Triple("mx4.kf3", 0, 0),            // /cgi-bin/micromsg-bin/getpoidetail
        716 to Triple("mx4.tc5", 0, 0),              // /cgi-bin/micromsg-bin/rtkvreport
        6691 to Triple("mx4.ur5", 0, 0),             // /cgi-bin/micromsg-bin/sendfileuploadmsg
        914 to Triple("mx4.d33", 0, 0),              // /cgi-bin/micromsg-bin/getapmstrategy
        411 to Triple("mx4.a93", 0, 0),              // /cgi-bin/micromsg-bin/getemotionlist
        1295 to Triple("mx4.y2", 0, 0),              // /cgi-bin/mmoc-bin/ad/addatareport
        148 to Triple("mx4.yz3", 0, 0),              // /cgi-bin/micromsg-bin/lbsfind
        850 to Triple("mx4.oh3", 0, 0),              // /cgi-bin/micromsg-bin/getsafetyinfo
        179 to Triple("mx4.u90", 0, 0),              // /cgi-bin/micromsg-bin/delchatroommember
        551 to Triple(
            "mx4.h73",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/getchatroommemberdetail
        182 to Triple("mx4.s73", 0, 0),              // /cgi-bin/micromsg-bin/getcontact
        6684 to Triple("mx4.pc3", 0, 0),             // /cgi-bin/micromsg-bin/getmidassdkinfo
        6619 to Triple("ox4.o", 0, 0),               // /cgi-bin/micromsg-bin/getfriendringback
        9120 to Triple("mx4.ht6", 0, 0),             // /cgi-bin/micromsg-bin/voipilinkgetsdkmode
        6472 to Triple(
            "o11.v9",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/musiclivesearchsmartbox
        4114 to Triple("o11.v9", 0, 0),              // /cgi-bin/micromsg-bin/musiclivesearch
        427 to Triple("of0.c", 0, 0),                // /cgi-bin/micromsg-bin/gamereportkv
        1311 to Triple(
            "hz2.u1",
            0,
            0
        ),              // /cgi-bin/mmgame-bin/getgamecenterglobalsetting
        1313 to Triple("mx4.q10", 0, 0),             // /cgi-bin/mmgame-bin/checkwepkgversion
        9175 to Triple(
            "hz2.o4",
            0,
            0
        ),              // /cgi-bin/mmgame-bin/gamemsgpushappsvr/pullusermessage
        19762 to Triple(
            "mx4.mn4",
            0,
            0
        ),            // /cgi-bin/mmemotionlogicsvr/newgetdesigneracctinfo
        12586 to Triple("mx4.oj5", 0, 0),            // /cgi-bin/micromsg-bin/emotionsdkreport
        1812 to Triple("mx4.o84", 0, 0),             // /cgi-bin/mmbiz-bin/usrmsg/mmbizscan_confsync
        4362 to Triple("mx4.q33", 0, 0),             // /cgi-bin/micromsg-bin/getallfunction
        425 to Triple("mx4.ob5", 209, 1000000209),   // /cgi-bin/micromsg-bin/mmradarsearch

        1948 to Triple("mx4.a37", 0, 0),             // /cgi-bin/mmsearch-bin/websearchconfig
        2975 to Triple("mx4.oq5", 0, 0),             // /cgi-bin/mmsearch-bin/searchwebquery
        1048 to Triple("mx4.lb4", 0, 0),             // /cgi-bin/mmsearch-bin/searchguide
        4534 to Triple("mx4.k64", 0, 0),             // /cgi-bin/mmsearch-bin/localsearchdict
        1417 to Triple("mx4.fy4", 0, 0),             // /cgi-bin/mmsearch-bin/parduspresearch
        1076 to Triple("mx4.ky4", 0, 0),             // /cgi-bin/mmsearch-bin/pardussearch
        1134 to Triple("mx4.jj5", 0, 0),             // /cgi-bin/mmsearch-bin/searchreport
        4773 to Triple("g31.b1", 0, 0),              // /cgi-bin/mmsearch-bin/searchsync
        5805 to Triple("mx4.lo3", 0, 0),             // /cgi-bin/micromsg-bin/h5auth
        25050 to Triple(
            "mx4.nt5",
            0,
            0
        ),            // /cgi-bin/mmbiz-bin/timeline/servicetimelineconfirm
        26449 to Triple("pm5.w6", 0, 0),             // /cgi-bin/mmbiz-bin/mmecbase/init
        29962 to Triple("pm5.r6", 0, 0),             // /cgi-bin/mmbiz-bin/mmec_getusershoplabel
        28920 to Triple("g31.b1", 0, 0),             // /cgi-bin/mmbiz-bin/mmec_ecsshopdata
        25694 to Triple(
            "g31.b1",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/mmec/timeline/ecsgetrecommendfeeds
        27078 to Triple("g31.b1", 0, 0),             // /cgi-bin/mmbiz-bin/mp/metabstrategy
        2723 to Triple("mx4.wj", 0, 0),              // /cgi-bin/mmbiz-bin/bizattr/bizstrategy
        4687 to Triple("g31.b1", 0, 0),              // /cgi-bin/mmbiz-bin/timeline/bizmsgresortv2
        4326 to Triple(
            "g31.b1",
            0,
            0
        ),              // /cgi-bin/mmbiz-bin/timeline/getrecommendfeedsv2
        29710 to Triple(
            "mx4.oj5",
            0,
            0
        ),            // /cgi-bin/mmbiz-bin/mmec/timeline-dc/amoebasdkreport
        8815 to Triple("g31.b1", 0, 0),              // /cgi-bin/micromsg-bin/newlifesync
        23111 to Triple("g31.p0", 0, 0),             // /cgi-bin/mmlistenappsvr/listennewsync
        14186 to Triple("g31.b1", 0, 0),             // /cgi-bin/micromsg-bin/mmecreddotsync
        18646 to Triple(
            "mx4.oo3",
            18646,
            0
        ),        // /cgi-bin/micromsg-bin/transfer/searchh5exttransfer
        18852 to Triple(
            "mx4.oo3",
            18852,
            0
        ),        // /cgi-bin/micromsg-bin/transfer/bizmaexttransfer
        2538 to Triple("mx4.o2", 0, 0),              // /cgi-bin/mmoc-bin/ad/adchannelmsg
        1286 to Triple(
            "mx4.h33",
            0,
            0
        ),             // /cgi-bin/mmoc-bin/adplayinfo/get_adcanvasinfo
        683 to Triple("mx4.a06", 0, 0),              // /cgi-bin/micromsg-bin/mmsnsadobjectdetail
        1122 to Triple("mx4.pz3", 0, 0),             // /cgi-bin/mmbiz-bin/wxaattr/launchwxaapp
        1151 to Triple("mx4.b67", 0, 0),             // /cgi-bin/mmbiz-bin/wxaattr/wxaattrsync
        2946 to Triple("mx4.um5", 0, 0),             // /cgi-bin/mmbiz-bin/wxartrappsvr/route
        7107 to Triple(
            "mx4.g6",
            0,
            0
        ),              // /cgi-bin/mmbiz-bin/wxabusiness/afterlaunchwxaapp
        4602 to Triple(
            "mx4.jw3",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/js-operatewxdata-keepalive
        1029 to Triple("mx4.hw3", 0, 0),             // /cgi-bin/mmbiz-bin/js-login
        1479 to Triple(
            "mx4.h45",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/wxasync/wxaapp_predownloadcode
        2921 to Triple(
            "mx4.sf3",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/wxabusiness/getprofileinfo
        1149 to Triple(
            "mx4.ul6",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/wxaapp/updatewxausagerecord
        1713 to Triple(
            "mx4.ze",
            0,
            0
        ),              // /cgi-bin/mmbiz-bin/wxaapp/batchgetwxadownloadurl
        1714 to Triple("mx4.cq6", 0, 0),             // /cgi-bin/mmbiz-bin/wxaapp/verifyplugin
        1733 to Triple("mx4.g67", 0, 0),             // /cgi-bin/mmbiz-bin/wxabusiness/fetchdata
        4024 to Triple(
            "mx4.r30",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/wxabusiness/coldstartfetchdata
        3827 to Triple("mx4.d77", 0, 0),             // /cgi-bin/mmbiz-bin/wxaattr/wxajsapiinfo
        1192 to Triple("mx4.vg", 0, 0),              // /cgi-bin/mmbiz-bin/wxaattr/batchwxaattrsync
        1009 to Triple(
            "mx4.r57",
            0,
            0
        ),             // /cgi-bin/mmbiz-bin/wxausrevent/wxaappidkeybatchreport

        4245 to Triple(
            "oc4.f1",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/textstatusgetselfhistories
        4099 to Triple("oc4.z1", 0, 0),              // /cgi-bin/micromsg-bin/textstatuslike
        4255 to Triple(
            "oc4.d1",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/textstatusgeticonconfig
        6868 to Triple(
            "oc4.l1",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/textstatusgetuserpermission
        5293 to Triple("oc4.o", 0, 0),               // /cgi-bin/micromsg-bin/modtextstatus
        5215 to Triple("oc4.h1", 0, 0),              // /cgi-bin/micromsg-bin/textstatusgetselfinfo
        5967 to Triple(
            "oc4.z0",
            0,
            0
        ),              // /cgi-bin/micromsg-bin/textstatusdeleteselfhistory
        9262 to Triple("g31.b1", 0, 0),              // /cgi-bin/micromsg-bin/voipilinklivesync
        10342 to Triple("g31.b1", 0, 0),             // /cgi-bin/micromsg-bin/voipilinkinvite
        9332 to Triple("g31.b1", 0, 0),              // /cgi-bin/micromsg-bin/voipilinkcancel
        11421 to Triple("mx4.es3", 0, 0),            // /cgi-bin/micromsg-bin/mmilinktransfer/...
        3847 to Triple("mx4.xb3", 0, 0),             // /cgi-bin/micromsg-bin/getemotiondetail
        720 to Triple("g31.p0", 0, 0),               // /cgi-bin/micromsg-bin/mmgetpersonaldesigner
        821 to Triple(
            "g31.p0",
            0,
            0
        ),                // /cgi-bin/micromsg-bin/mmgetdesigneremojilist
        594 to Triple("mx4.sl5", 0, 0),              // /cgi-bin/micromsg-bin/revokemsg
        681 to Triple("mx4.jv4", 0, 0),               // /cgi-bin/micromsg-bin/oplog
    )
}