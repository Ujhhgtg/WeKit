package dev.ujhhgtg.wekit.data.structured

/**
 * Schema of the generic automation tables (see
 * [dev.ujhhgtg.wekit.data.entity.AutomationScopeEntity]).
 */
val automationSchemaSql =
    listOf(
        "CREATE TABLE IF NOT EXISTS `automation_scopes` (`feature` TEXT NOT NULL, `scopeType` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `name` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`feature`, `scopeType`, `scopeId`, `memberId`))",
        "CREATE TABLE IF NOT EXISTS `automation_scope_fields` (`feature` TEXT NOT NULL, `scopeType` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `ruleKey` TEXT NOT NULL, `field` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`feature`, `scopeType`, `scopeId`, `memberId`, `ruleKey`, `field`), FOREIGN KEY(`feature`, `scopeType`, `scopeId`, `memberId`) REFERENCES `automation_scopes`(`feature`, `scopeType`, `scopeId`, `memberId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `automation_scope_list_values` (`feature` TEXT NOT NULL, `scopeType` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `ruleKey` TEXT NOT NULL, `field` TEXT NOT NULL, `position` INTEGER NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`feature`, `scopeType`, `scopeId`, `memberId`, `ruleKey`, `field`, `position`), FOREIGN KEY(`feature`, `scopeType`, `scopeId`, `memberId`) REFERENCES `automation_scopes`(`feature`, `scopeType`, `scopeId`, `memberId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `automation_scope_presets` (`feature` TEXT NOT NULL, `scopeType` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `position` INTEGER NOT NULL, `presetId` TEXT NOT NULL, PRIMARY KEY(`feature`, `scopeType`, `scopeId`, `memberId`, `position`), FOREIGN KEY(`feature`, `scopeType`, `scopeId`, `memberId`) REFERENCES `automation_scopes`(`feature`, `scopeType`, `scopeId`, `memberId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

/**
 * 23 → 24: move the per-feature red packet / transfer / moments rule tables into the generic
 * automation tables. Rule keys and field names must match the feature specs and
 * [dev.ujhhgtg.wekit.features.automation.AutomationCodecs]. Reply text becomes a one-step reply.
 */
val migration23To24Sql: List<String> = buildList {
    addAll(automationSchemaSql)

    class Source(
        val feature: String,
        val table: String,
        val scopeId: String,
        val memberId: String,
        val where: String = "1",
    )

    fun bool(column: String) = "CASE WHEN `$column` THEN 'true' ELSE 'false' END"

    fun scalar(
        src: Source,
        ruleKey: String,
        field: String,
        expression: String,
        presentColumn: String,
    ) =
        add(
            "INSERT INTO `automation_scope_fields` SELECT '${src.feature}', `scopeType`, ${src.scopeId}, ${src.memberId}, " +
                "'$ruleKey', '$field', $expression FROM `${src.table}` WHERE ${src.where} AND `$presentColumn` IS NOT NULL",
        )

    fun toggle(src: Source, ruleKey: String, column: String) =
        scalar(src, ruleKey, "enabled", bool(column), column)

    fun timeRange(src: Source, ruleKey: String) {
        toggle(src, ruleKey, "timeEnabled")
        scalar(src, ruleKey, "startSecond", "CAST(`timeStartMinute` * 60 AS TEXT)", "timeEnabled")
        scalar(src, ruleKey, "endSecond", "CAST(`timeEndMinute` * 60 AS TEXT)", "timeEnabled")
    }

    fun keyword(src: Source, ruleKey: String, prefix: String) {
        toggle(src, ruleKey, "${prefix}Enabled")
        scalar(src, ruleKey, "mode", "`${prefix}Mode`", "${prefix}Enabled")
        scalar(src, ruleKey, "regex", "`${prefix}Regex`", "${prefix}Enabled")
        scalar(src, ruleKey, "ignoreCase", bool("${prefix}IgnoreCase"), "${prefix}Enabled")
    }

    fun delay(src: Source) {
        toggle(src, "delay", "delayEnabled")
        scalar(src, "delay", "baseMs", "`delayBaseMs`", "delayEnabled")
        scalar(src, "delay", "randomRangeMs", "`delayRandomRangeMs`", "delayEnabled")
    }

    fun reply(src: Source) {
        toggle(src, "reply", "replyEnabled")
        scalar(src, "reply", "asQuote", "'false'", "replyEnabled")
        listOf(
                "step.kind" to "'TEXT'",
                "step.content" to "`replyText`",
                "step.delayMs" to "'0'",
                "step.randomDelay" to "'false'",
            )
            .forEach { (field, expression) ->
                add(
                    "INSERT INTO `automation_scope_list_values` SELECT '${src.feature}', `scopeType`, ${src.scopeId}, ${src.memberId}, " +
                        "'reply', '$field', 0, $expression FROM `${src.table}` WHERE `replyEnabled` IS NOT NULL AND `replyText` <> ''",
                )
            }
    }

    fun keywordList(
        feature: String,
        table: String,
        ruleKey: String,
        scopeId: String,
        memberId: String,
        where: String,
    ) =
        add(
            "INSERT INTO `automation_scope_list_values` SELECT '$feature', `scopeType`, $scopeId, $memberId, " +
                "'$ruleKey', 'strings', `position`, `text` FROM `$table` WHERE $where",
        )

    fun scopes(src: Source) =
        add(
            "INSERT INTO `automation_scopes` SELECT '${src.feature}', `scopeType`, ${src.scopeId}, ${src.memberId}, NULL, 0 " +
                "FROM `${src.table}` WHERE ${src.where}",
        )

    // Red packets
    val redPacket = Source("RED_PACKET", "red_packet_rules", "`talkerId`", "`memberId`")
    scopes(redPacket)
    toggle(redPacket, "grab", "grabEnabled")
    toggle(redPacket, "grabSelf", "grabSelfEnabled")
    scalar(redPacket, "receiveMode", "value", "`receiveMode`", "receiveMode")
    timeRange(redPacket, "timeRange")
    keyword(redPacket, "keyword", "keyword")
    keyword(redPacket, "skipKeyword", "skipKeyword")
    delay(redPacket)
    toggle(redPacket, "notification", "notificationEnabled")
    reply(redPacket)
    keywordList(
        "RED_PACKET",
        "red_packet_keywords",
        "keyword",
        "`talkerId`",
        "`memberId`",
        "`ruleKind` = 'KEYWORD'",
    )
    keywordList(
        "RED_PACKET",
        "red_packet_keywords",
        "skipKeyword",
        "`talkerId`",
        "`memberId`",
        "`ruleKind` = 'SKIP_KEYWORD'",
    )

    // Transfers
    val transfer = Source("TRANSFER", "transfer_rules", "`talkerId`", "`memberId`")
    scopes(transfer)
    toggle(transfer, "accept", "acceptEnabled")
    timeRange(transfer, "timeRange")
    toggle(transfer, "amountRange", "amountEnabled")
    scalar(transfer, "amountRange", "minimumYuan", "`amountMinimumYuan`", "amountEnabled")
    scalar(transfer, "amountRange", "maximumYuan", "`amountMaximumYuan`", "amountEnabled")
    keyword(transfer, "memoKeyword", "memoKeyword")
    delay(transfer)
    toggle(transfer, "notification", "notificationEnabled")
    reply(transfer)
    keywordList("TRANSFER", "transfer_keywords", "memoKeyword", "`talkerId`", "`memberId`", "1")

    // Moments
    listOf("LIKE" to "MOMENTS_LIKE", "REPOST" to "MOMENTS_REPOST").forEach { (kind, feature) ->
        val moments =
            Source(feature, "moment_automation_rules", "`ownerId`", "''", "`featureKind` = '$kind'")
        scopes(moments)
        toggle(moments, "process", "processEnabled")
        // Only auto-like has an action rule; the shared table also stored it for repost rows.
        if (kind == "LIKE") {
            toggle(moments, "action", "actionEnabled")
            scalar(moments, "action", "action", "`actionValue`", "actionEnabled")
        }
        toggle(moments, "mode", "modeEnabled")
        scalar(moments, "mode", "mode", "`modeValue`", "modeEnabled")
        toggle(moments, "interval", "intervalEnabled")
        scalar(moments, "interval", "milliseconds", "`intervalMilliseconds`", "intervalEnabled")
        timeRange(moments, "timeRange")
        keyword(moments, "keyword", "keyword")
        toggle(moments, "contentType", "contentTypeEnabled")
        toggle(moments, "maximumAge", "maximumAgeEnabled")
        scalar(moments, "maximumAge", "maximumHours", "`maximumAgeHours`", "maximumAgeEnabled")
        keywordList(
            feature,
            "moment_automation_keywords",
            "keyword",
            "`ownerId`",
            "''",
            "`featureKind` = '$kind'",
        )
        add(
            "INSERT INTO `automation_scope_list_values` SELECT '$feature', r.`scopeType`, r.`ownerId`, '', 'contentType', 'typeIds', " +
                "(SELECT COUNT(*) FROM `moment_automation_content_types` p WHERE p.`featureKind` = r.`featureKind` AND p.`scopeType` = r.`scopeType` AND p.`ownerId` = r.`ownerId` AND p.`typeId` < r.`typeId`), " +
                "CAST(r.`typeId` AS TEXT) FROM `moment_automation_content_types` r WHERE r.`featureKind` = '$kind'",
        )
    }

    // Keyword/type rows of a rule group that was inherited are orphans in the old schema; drop
    // them.
    add(
        "DELETE FROM `automation_scope_list_values` WHERE NOT EXISTS (SELECT 1 FROM `automation_scope_fields` f " +
            "WHERE f.`feature` = `automation_scope_list_values`.`feature` AND f.`scopeType` = `automation_scope_list_values`.`scopeType` " +
            "AND f.`scopeId` = `automation_scope_list_values`.`scopeId` AND f.`memberId` = `automation_scope_list_values`.`memberId` " +
            "AND f.`ruleKey` = `automation_scope_list_values`.`ruleKey`)",
    )

    listOf(
            "red_packet_keywords",
            "transfer_keywords",
            "moment_automation_keywords",
            "moment_automation_content_types",
            "red_packet_rules",
            "transfer_rules",
            "moment_automation_rules",
        )
        .forEach { add("DROP TABLE `$it`") }
}

/** 25 → 26: time-range rules move from minutes to seconds of the day (Hchat's precision). */
val migration25To26Sql: List<String> =
    listOf("start", "end").map { bound ->
        "UPDATE `automation_scope_fields` SET `field` = '${bound}Second', `value` = CAST(CAST(`value` AS INTEGER) * 60 AS TEXT) " +
            "WHERE `field` = '${bound}Minute'"
    }
