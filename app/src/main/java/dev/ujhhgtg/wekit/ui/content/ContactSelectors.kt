package dev.ujhhgtg.wekit.ui.content

import android.icu.text.Transliterator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Tag
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Sort_by_alpha
import com.composables.icons.materialsymbols.outlined.Schedule
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup
import dev.ujhhgtg.wekit.features.api.core.models.WeOfficialAccount
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

enum class FilterType(val displayName: String) {
    ALL("全部"),
    FRIENDS("好友"),
    GROUPS("群聊"),
    OFFICIAL_ACCOUNTS("公众号"),
    OTHERS("其他")
}

enum class SortMode(val displayName: String, val icon: ImageVector) {
    ALPHABETICAL("A-Z", MaterialSymbols.Outlined.Sort_by_alpha),
    LAST_MESSAGE_TIME("最近消息", MaterialSymbols.Outlined.Schedule)
}

@Composable
fun BaseContactSelector(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredContacts: List<IWeContact>,
    confirmButtonText: String,
    confirmButtonEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: Any,
    isSelected: (IWeContact) -> Boolean,
    showConfirmButton: Boolean = true,
    dismissButtonText: String = "取消",
    avatarModelProvider: ((IWeContact) -> Any)? = { it.avatarUrl },
    subtitleProvider: ((IWeContact) -> String)? = { it.wxId },
    leadingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    trailingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    onItemClick: (IWeContact) -> Unit,
    onSelectAll: ((List<IWeContact>) -> Unit)? = null,
    onDeselectAll: ((List<IWeContact>) -> Unit)? = null,
    onInvertSelection: ((List<IWeContact>) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { listOf("已选") + ('A'..'Z').map { it.toString() } + "#" }

    val transliterator = remember {
        try {
            Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
        } catch (_: Exception) {
            null
        }
    }

    var friendWxIds by remember { mutableStateOf(emptySet<String>()) }
    var groupWxIds by remember { mutableStateOf(emptySet<String>()) }
    var officialAccountWxIds by remember { mutableStateOf(emptySet<String>()) }
    var allLabels by remember { mutableStateOf(emptyList<WeContactLabelApi.ContactLabel>()) }
    var labelContactsMap by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var isFiltersLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (WeDatabaseApi.isReady) {
                    val friends = WeDatabaseApi.getFriends().map { it.wxId }.toSet()
                    val groups = WeDatabaseApi.getGroups().map { it.wxId }.toSet()
                    val officialAccounts = WeDatabaseApi.getOfficialAccounts().map { it.wxId }.toSet()
                    val labels = WeContactLabelApi.getAllLabels()
                    val labelMap = labels.associate { label ->
                        label.labelName to WeContactLabelApi.getContactsByLabelId(label.labelId).toSet()
                    }

                    withContext(Dispatchers.Main) {
                        friendWxIds = friends
                        groupWxIds = groups
                        officialAccountWxIds = officialAccounts
                        allLabels = labels
                        labelContactsMap = labelMap
                        isFiltersLoaded = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("数据库尚未初始化, 筛选将不可用!")
                        isFiltersLoaded = true
                    }
                }
            } catch (e: Exception) {
                WeLogger.e("ContactSelectors", "Failed to load filters in coroutine", e)
                withContext(Dispatchers.Main) {
                    isFiltersLoaded = true
                }
            }
        }
    }

    var selectedType by remember { mutableStateOf(FilterType.ALL) }
    var selectedLabelName by remember { mutableStateOf<String?>(null) }

    var sortMode by remember { mutableStateOf(SortMode.ALPHABETICAL) }
    var lastMessageTimes by remember { mutableStateOf<Map<String, Long>?>(null) }
    var isSortLoading by remember { mutableStateOf(false) }

    fun switchSortMode(target: SortMode) {
        if (target == sortMode || isSortLoading) return
        if (target == SortMode.ALPHABETICAL) {
            sortMode = SortMode.ALPHABETICAL
            return
        }
        // 切换到按最近消息时间排序
        if (lastMessageTimes != null) {
            sortMode = SortMode.LAST_MESSAGE_TIME
            return
        }
        isSortLoading = true
        coroutineScope.launch {
            val times = withContext(Dispatchers.IO) {
                if (WeDatabaseApi.isReady) WeDatabaseApi.getLastMessageTimes() else null
            }
            if (times == null) {
                showToast("数据库尚未初始化, 无法按时间排序!")
            } else {
                lastMessageTimes = times
                sortMode = SortMode.LAST_MESSAGE_TIME
            }
            isSortLoading = false
        }
    }

    val typeCounts = remember(filteredContacts, friendWxIds, groupWxIds, officialAccountWxIds) {
        var friends = 0
        var groups = 0
        var officialAccounts = 0
        var others = 0
        for (contact in filteredContacts) {
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
            when {
                isGroup -> groups++
                isOfficial -> officialAccounts++
                isFriend -> friends++
                else -> others++
            }
        }
        mapOf(
            FilterType.ALL to filteredContacts.size,
            FilterType.FRIENDS to friends,
            FilterType.GROUPS to groups,
            FilterType.OFFICIAL_ACCOUNTS to officialAccounts,
            FilterType.OTHERS to others
        )
    }

    val availableTypes = remember(typeCounts) {
        FilterType.entries.filter { type ->
            type == FilterType.ALL || typeCounts[type] ?: 0 > 0
        }
    }
    val showTypeFilterRow = remember(availableTypes, isFiltersLoaded) { isFiltersLoaded && availableTypes.size > 2 }


    val labelCounts = remember(filteredContacts, labelContactsMap) {
        labelContactsMap.mapValues { (_, wxIds) ->
            filteredContacts.count { it.wxId in wxIds }
        }
    }
    val availableLabels = remember(allLabels, labelCounts) {
        allLabels.filter { label ->
            labelCounts[label.labelName] ?: 0 > 0
        }
    }
    val showLabelFilterRow = remember(availableLabels, isFiltersLoaded) { isFiltersLoaded && availableLabels.isNotEmpty() }

    val displayedContacts = remember(filteredContacts, selectedType, selectedLabelName, friendWxIds, groupWxIds, officialAccountWxIds, labelContactsMap) {
        filteredContacts.filter { contact ->
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0

            val matchesType = when (selectedType) {
                FilterType.ALL -> true
                FilterType.FRIENDS -> isFriend
                FilterType.GROUPS -> isGroup
                FilterType.OFFICIAL_ACCOUNTS -> isOfficial
                FilterType.OTHERS -> !isFriend && !isGroup && !isOfficial
            }

            val matchesLabel = if (selectedLabelName == null) {
                true
            } else {
                val labelWxIds = labelContactsMap[selectedLabelName] ?: emptySet()
                contact.wxId in labelWxIds
            }

            matchesType && matchesLabel
        }
    }

    val groupedContacts = remember(displayedContacts, transliterator, selectionKey, sortMode, lastMessageTimes) {
        if (sortMode == SortMode.LAST_MESSAGE_TIME) {
            val times = lastMessageTimes ?: emptyMap()
            val sorted = displayedContacts.sortedByDescending { times[it.wxId] ?: Long.MIN_VALUE }
            val (selected, rest) = sorted.partition { isSelected(it) }
            linkedMapOf<String, List<IWeContact>>().apply {
                if (selected.isNotEmpty()) put("已选", selected)
                if (rest.isNotEmpty()) put("最近消息", rest)
            }
        } else {
            displayedContacts.groupBy { contact ->
                if (isSelected(contact)) {
                    "已选"
                } else {
                    val name = contact.displayName.trim()
                    if (name.isEmpty()) return@groupBy "#"

                    val firstChar = name.first()
                    if (firstChar.uppercaseChar() in 'A'..'Z') {
                        firstChar.uppercaseChar().toString()
                    } else if (transliterator != null) {
                        val pinyin = transliterator.transliterate(firstChar.toString())
                        val initial = pinyin.firstOrNull()?.uppercaseChar() ?: '#'
                        if (initial in 'A'..'Z') initial.toString() else "#"
                    } else {
                        "#"
                    }
                }
            }.toSortedMap { c1, c2 ->
                when {
                    c1 == c2 -> 0
                    c1 == "已选" -> -1
                    c2 == "已选" -> 1
                    c1 == "#" -> 1
                    c2 == "#" -> -1
                    else -> c1.compareTo(c2)
                }
            }
        }
    }

    val sectionIndices = remember(groupedContacts) {
        val mapping = mutableMapOf<String, Int>()
        var currentFlatIndex = 0
        groupedContacts.forEach { (letter, contactsInGroup) ->
            mapping[letter] = currentFlatIndex
            currentFlatIndex += 1
            currentFlatIndex += contactsInGroup.size
        }
        mapping
    }

    AlertDialogContent(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    placeholder = { Text("搜索昵称或微信号") },
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = "Search") },
                    singleLine = true
                )

                if (showTypeFilterRow) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(availableTypes) { type ->
                            val isSelected = selectedType == type
                            val count = typeCounts[type] ?: 0
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedType = type },
                                label = { Text("${type.displayName} ($count)") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (type) {
                                            FilterType.ALL -> MaterialSymbols.Outlined.Search
                                            FilterType.FRIENDS -> MaterialSymbols.Outlined.Person
                                            FilterType.GROUPS -> MaterialSymbols.Outlined.Groups
                                            FilterType.OFFICIAL_ACCOUNTS -> MaterialSymbols.Outlined.Chat
                                            FilterType.OTHERS -> MaterialSymbols.Outlined.Tag
                                        },
                                        contentDescription = type.displayName,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                if (showLabelFilterRow) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Label,
                                contentDescription = "标签",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        item {
                            val isSelected = selectedLabelName == null
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedLabelName = null },
                                label = { Text("全部") }
                            )
                        }

                        items(availableLabels) { label ->
                            val isSelected = selectedLabelName == label.labelName
                            val labelCount = labelCounts[label.labelName] ?: 0
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedLabelName = if (isSelected) null else label.labelName },
                                label = { Text("${label.labelName} ($labelCount)") }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SortMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sortMode == mode,
                            enabled = !isSortLoading,
                            onClick = { switchSortMode(mode) },
                            label = { Text(mode.displayName) },
                            leadingIcon = {
                                if (isSortLoading && mode == SortMode.LAST_MESSAGE_TIME) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = mode.icon,
                                        contentDescription = mode.displayName,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                if (onSelectAll != null || onDeselectAll != null || onInvertSelection != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        onSelectAll?.let {
                            FilterChip(
                                selected = false,
                                onClick = { it(displayedContacts) },
                                label = { Text("全选") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Select_all,
                                        contentDescription = "全选",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        onDeselectAll?.let {
                            FilterChip(
                                selected = false,
                                onClick = { it(displayedContacts) },
                                label = { Text("全不选") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Deselect,
                                        contentDescription = "全不选",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        onInvertSelection?.let {
                            FilterChip(
                                selected = false,
                                onClick = { it(displayedContacts) },
                                label = { Text("反选") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Compare_arrows,
                                        contentDescription = "反选",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (displayedContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "无匹配的联系人",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            groupedContacts.forEach { (letter, contactsInGroup) ->
                                stickyHeader(key = "header_$letter") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = if (letter == "已选") "已选" else letter,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                items(
                                    items = contactsInGroup,
                                    key = { it.wxId }
                                ) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .clickable { onItemClick(contact) }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (leadingControl != null) {
                                            leadingControl(contact)
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }

                                        AsyncImage(
                                            model = avatarModelProvider?.invoke(contact) ?: contact.avatarUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            imageLoader = GlobalImageLoader
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.displayName,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = subtitleProvider?.invoke(contact) ?: contact.wxId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (trailingControl != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            trailingControl(contact)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 8.dp, end = 4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (sortMode == SortMode.ALPHABETICAL) alphabet.forEach { letter ->
                                val isAvailable = groupedContacts.containsKey(letter)
                                Text(
                                    text = if (letter == "已选") "✓" else letter,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAvailable) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            val targetIndex = if (letter == "已选") {
                                                sectionIndices["已选"]
                                            } else {
                                                val targetLetter = sectionIndices.keys
                                                    .filter { it != "已选" }
                                                    .firstOrNull { it.first() >= letter.first() }
                                                    ?: sectionIndices.keys.lastOrNull()
                                                targetLetter?.let { sectionIndices[it] }
                                            }
                                            targetIndex?.let { index ->
                                                coroutineScope.launch {
                                                    listState.scrollToItem(index)
                                                }
                                            }
                                        }
                                        .padding(vertical = 2.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(dismissButtonText) }
        },
        confirmButton = if (showConfirmButton) {
            {
                Button(
                    onClick = onConfirm,
                    enabled = confirmButtonEnabled
                ) {
                    Text(confirmButtonText)
                }
            }
        } else null
    )
}

@Composable
fun SingleContactSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxId by remember { mutableStateOf(initialSelectedWxId) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        confirmButtonText = "确定",
        confirmButtonEnabled = selectedWxId != null,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxId!!) },
        selectionKey = selectedWxId ?: "",
        isSelected = { it.wxId == selectedWxId },
        leadingControl = { contact ->
            RadioButton(
                selected = contact.wxId == selectedWxId,
                onClick = null
            )
        },
        onItemClick = { contact ->
            selectedWxId = contact.wxId
        }
    )
}

@Composable
fun ContactsSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        confirmButtonText = "确定 (${selectedWxIds.size})",
        confirmButtonEnabled = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxIds) },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        leadingControl = { contact ->
            Checkbox(
                checked = contact.wxId in selectedWxIds,
                onCheckedChange = null
            )
        },
        onItemClick = { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) {
                selectedWxIds - contact.wxId
            } else {
                selectedWxIds + contact.wxId
            }
        },
        onSelectAll = { displayed ->
            selectedWxIds = selectedWxIds + displayed.map { it.wxId }
        },
        onDeselectAll = { displayed ->
            selectedWxIds = selectedWxIds - displayed.map { it.wxId }.toSet()
        },
        onInvertSelection = { displayed ->
            val displayedWxIds = displayed.map { it.wxId }.toSet()
            val newSelection = selectedWxIds.toMutableSet()
            for (wxId in displayedWxIds) {
                if (wxId in newSelection) {
                    newSelection.remove(wxId)
                } else {
                    newSelection.add(wxId)
                }
            }
            selectedWxIds = newSelection
        }
    )
}
