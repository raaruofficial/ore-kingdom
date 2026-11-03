package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.ui.ForumViewModel

/**
 * The M1 home: the forum's thread list, cold, with no wallet — reading
 * is public exactly as the sealed build behaves. The payload renders in
 * the host's own order (pinned first, then last activity); the River's
 * client-side surfaces (R7–R10) land in M2 on top of this list.
 */
@Composable
fun ThreadListScreen(navController: NavController, vm: ForumViewModel) {
    val forumState by vm.forumState.collectAsState()
    val member by vm.member.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()

    Scaffold(
        topBar = {
            OreTopBar("Ore Kingdom", navController) {
                IconButton(onClick = { vm.loadForum() }) {
                    Text("↻", style = MaterialTheme.typography.titleLarge)
                }
                androidx.compose.material3.TextButton(
                    onClick = { navController.navigate("signin") }
                ) {
                    Text(member?.skrName ?: "Sign in")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = forumState) {
                ForumViewModel.LoadState.Loading -> LoadingBox()
                ForumViewModel.LoadState.Unreachable ->
                    HostUnreachable(onRetry = { vm.loadForum() })
                is ForumViewModel.LoadState.Ready<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val payload = state.value as com.raaru.orekingdom.data.ForumPayload
                    if (state.stale) {
                        StaleBanner(onRetry = { vm.loadForum() })
                    }
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { vm.selectCategory(null) },
                            label = { Text("All") }
                        )
                        payload.categories.forEach { category ->
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = selectedCategory == category.id,
                                onClick = { vm.selectCategory(category.id) },
                                label = { Text("${category.name} (${category.count})") }
                            )
                        }
                    }
                    if (payload.threads.isEmpty()) {
                        Text(
                            "No threads here yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(payload.threads, key = { it.id }) { thread ->
                                ThreadRow(thread) {
                                    navController.navigate("thread/${thread.id}")
                                }
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}
