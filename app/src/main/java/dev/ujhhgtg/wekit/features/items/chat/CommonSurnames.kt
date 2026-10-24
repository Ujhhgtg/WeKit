package dev.ujhhgtg.wekit.features.items.chat

/**
 * Common Chinese single-character surnames, roughly ordered by population frequency (百家姓 / modern
 * census orderings). The first ~100 cover the large majority of the population; the tail adds
 * long-tail coverage. Used by [BruteForceGroupMemberRealNamesFirstChar] as the candidate space when
 * brute-forcing the first character of a real name.
 */
val COMMON_SURNAMES: List<String> =
    ("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾" +
            "萧田董袁潘于蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏" +
            "韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴" +
            "莫孔向汤倪施兰卞计阮瞿初喻邢朴奚")
        .map { it.toString() }
