package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.vipCategory
import com.raaru.orekingdom.ui.ForumViewModel
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * The minimal thread composer (M2): title, body, board. It calls
 * createThread through the wrapper's post gate with the member's
 * SIWS proof — no payment session is attached, because that wiring
 * is the M3 build. What that means, truthfully: a prestige holder's
 * thread passes the gate session-free (the wrapper reads the tier's
 * status itself), and a thread into the VIP board passes only for a
 * holder; any other gate refusal — the standing thread fee among
 * them — comes back as the host's own words and is shown verbatim.
 * Nothing here pretends a refused post landed.
 *
 * [preselectCategoryId] 0 = no preselection (first board); the River
 * passes the current board view's id when there is one.
 */
@Composable
fun ComposerScreen(
    navController: NavController,
    vm: ForumViewModel,
    sender: ActivityResultSender,
    preselectCategoryId: Int
) {
    val forumState by vm.forumState.collectAsState()
    val posting by vm.posting.collectAsState()
    val payload = (forumState as? ForumViewModel.LoadState.Ready<*>)?.let {
        @Suppress("UNCHECKED_CAST")
        it.value as ForumPayload
    }
    val categories = payload?.categories ?: emptyList()
    val vipId = payload?.let { vipCategory(it)?.id }

    var selectedCategoryId by remember(preselectCategoryId, categories) {
        mutableStateOf(
            when {
                preselectCategoryId != 0 &&
                    categories.any { it.id == preselectCategoryId } -> preselectCategoryId
                else -> categories.firstOrNull()?.id ?: 0
            }
        )
    }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    val canPost = !posting && selectedCategoryId != 0 &&
        title.isNotBlank() && body.isNotBlank()

    Scaffold(topBar = { OreTopBar("New thread", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Board", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategoryId == category.id,
                        onClick = { selectedCategoryId = category.id },
                        label = {
                            Text((if (category.id == vipId) "🔒 " else "") + category.name)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (selectedCategoryId == vipId) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The prestige board: holders post here fee-free; " +
                        "the host refuses everyone else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Body") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    error = null
                    vm.postThread(sender, selectedCategoryId, title, body) { err ->
                        if (err == null) {
                            navController.popBackStack()
                        } else {
                            error = err
                        }
                    }
                },
                enabled = canPost,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (posting) "Posting…" else "Post thread")
            }
        }
    }
}
