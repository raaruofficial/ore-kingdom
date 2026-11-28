package com.raaru.orekingdom.passes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.RoleBadge
import com.raaru.orekingdom.designsystem.components.roleColor
import com.raaru.orekingdom.designsystem.components.roleLabel
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.Entitlements

enum class PassStatus { Loading, Ready, Error }

data class PassRecipientUi(val userId: String, val displayName: String)

/**
 * Pass screen state. [lifetimeHolder] and [prestigeSeatsRemaining] are host-supplied (TODO-CONTRACT):
 * the screen compares no role names and computes nothing. Null seats = not supplied, not rendered.
 */
data class PassScreenUi(
    val status: PassStatus = PassStatus.Loading,
    val lifetimeHolder: Boolean = false,
    val prestigeSeatsRemaining: Int? = null,
    val recipients: List<PassRecipientUi> = emptyList(),
)

/** One priced option. Strings of record: rendered exactly, never computed or converted. */
internal data class PassOption(val id: String, val price: String, val ore: String?)

internal data class PassProduct(
    val id: String,
    val role: OreRole,
    val options: List<PassOption>,
    val includes: String? = null,
    val prestige: Boolean = false,
)

internal object PassCopy {
    const val TITLE = "Passes"
    const val NO_AUTO_RENEW = "Nothing auto-renews. Every period is a fresh, deliberate purchase."
    const val FREE_FOREVER = "Reading is free forever."
    const val FREE_LINE = "Free (Reader) — $0"
    const val ORE_PREFIX = "ORE display:"
    const val GRANTS = "Grants"
    const val LIFETIME_TITLE = "Lifetime access"
    const val LIFETIME_BODY = "You hold Prestige for life. There is nothing to buy and nothing to renew."
    const val EXPIRY_HEADER = "CURRENT PASS"
    const val EXPIRY_LABEL = "Active until"
    const val EXTEND_NOTE = "Renewing extends from this expiry. Paid time is never shortened."
    const val MODE_SELF = "For me"
    const val MODE_GIFT = "Gift this pass"
    const val GIFT_UNAVAILABLE = "Gifting is not part of your current access."
    const val RECIPIENT_HEADER = "RECIPIENT"
    const val RECIPIENTS_EMPTY = "No recipients to choose from yet."
    const val RECIPIENT_NEEDED = "Choose a recipient to gift a pass."
    const val GET = "Get"
    const val EXTEND = "Extend"
    const val GIFT = "Gift"
    const val SEATS_SUFFIX = "of 100 seats remaining"
    const val ERROR = "Passes could not be loaded."
    const val RETRY = "Retry"
    const val SKELETON_CARDS = 4

    val PRODUCTS = listOf(
        PassProduct(
            id = "member", role = OreRole.Member,
            options = listOf(
                PassOption("7d", "$7 / 7 days", "$4.90"),
                PassOption("30d", "$20 / 30 days", "$14.00"),
            ),
        ),
        PassProduct(
            id = "operator", role = OreRole.Operator,
            options = listOf(
                PassOption("7d", "$49 / 7 days", "$34.30"),
                PassOption("30d", "$99 / 30 days", "$69.30"),
            ),
            includes = "Includes unlimited Pump Days, the ATM unlock, and Nightshift intel",
        ),
        PassProduct(
            id = "syndicate", role = OreRole.Syndicate,
            options = listOf(PassOption("30d", "$299 / 30 days", "$209.30")),
        ),
        PassProduct(
            id = "prestige", role = OreRole.Prestige,
            options = listOf(PassOption("lifetime", "10,000 SKR — one-time, lifetime", null)),
            prestige = true,
        ),
    )
}

/**
 * Pass screen: every product with its display strings of record. Renders from [entitlements]
 * (expiry verbatim, gift capability) and [state]; computes nothing, converts nothing.
 * [onPurchase] and [onGift] are stub callbacks (TODO-CONTRACT): no value moves here.
 * A lifetime holder is shown no purchase offer of any kind.
 */
@Composable
fun PassScreen(
    state: PassScreenUi,
    entitlements: Entitlements?,
    modifier: Modifier = Modifier,
    onPurchase: (productId: String, optionId: String) -> Unit = { _, _ -> },
    onGift: (productId: String, optionId: String, recipientUserId: String) -> Unit = { _, _, _ -> },
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    var giftMode by rememberSaveable { mutableStateOf(false) }
    var recipientId by rememberSaveable { mutableStateOf<String?>(null) }
    val canGift = entitlements?.capabilities?.gift == true
    val gifting = giftMode && canGift
    val recipient = state.recipients.firstOrNull { it.userId == recipientId }
    val expiresAt = entitlements?.expiresAt

    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Box(
            Modifier.fillMaxWidth().background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
        ) { OreText(text = PassCopy.TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary, maxLines = 1) }
        when {
            state.status == PassStatus.Error -> PassCard(Modifier.padding(space.md)) {
                OreText(text = PassCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = PassCopy.RETRY, onClick = onRetry)
            }
            state.status == PassStatus.Loading || entitlements == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                repeat(PassCopy.SKELETON_CARDS) {
                    Box(Modifier.fillMaxWidth().height(OreTheme.size.touchTarget * 2).background(OreTheme.color.surface.highlight))
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                item(key = "standing") {
                    PassCard {
                        OreText(text = PassCopy.NO_AUTO_RENEW, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary)
                        OreText(text = PassCopy.FREE_FOREVER, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                        OreText(text = PassCopy.FREE_LINE, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
                    }
                }
                if (state.lifetimeHolder) {
                    item(key = "lifetime") {
                        PassCard {
                            RoleBadge(role = OreRole.Prestige)
                            OreText(text = PassCopy.LIFETIME_TITLE, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary)
                            OreText(text = PassCopy.LIFETIME_BODY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                        }
                    }
                } else {
                    if (expiresAt != null) {
                        item(key = "expiry") {
                            PassCard {
                                PassOverline(PassCopy.EXPIRY_HEADER)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                                    OreText(
                                        text = PassCopy.EXPIRY_LABEL,
                                        style = OreTheme.type.body,
                                        color = OreTheme.color.text.secondary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    OreText(text = expiresAt, style = OreTheme.type.numeric, color = OreTheme.color.text.primary, maxLines = 1)
                                }
                                OreText(text = PassCopy.EXTEND_NOTE, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
                            }
                        }
                    }
                    item(key = "mode") {
                        Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                                PassModeTab(PassCopy.MODE_SELF, selected = !gifting, enabled = true, Modifier.weight(1f)) { giftMode = false }
                                PassModeTab(PassCopy.MODE_GIFT, selected = gifting, enabled = canGift, Modifier.weight(1f)) { giftMode = true }
                            }
                            if (!canGift) {
                                OreText(text = PassCopy.GIFT_UNAVAILABLE, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
                            }
                        }
                    }
                    if (gifting) {
                        item(key = "recipient_header") { PassOverline(PassCopy.RECIPIENT_HEADER) }
                        if (state.recipients.isEmpty()) {
                            item(key = "recipient_empty") {
                                PassCard {
                                    OreText(text = PassCopy.RECIPIENTS_EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                                }
                            }
                        } else {
                            items(state.recipients, key = { "recipient_${it.userId}" }) { r ->
                                val selected = r.userId == recipientId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(OreTheme.size.touchTarget)
                                        .background(if (selected) OreTheme.color.accent.subtle else OreTheme.color.surface.raised)
                                        .clickable { recipientId = r.userId }
                                        .padding(horizontal = space.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OreText(
                                        text = r.displayName,
                                        style = OreTheme.type.body,
                                        color = if (selected) OreTheme.color.accent.onSubtle else OreTheme.color.text.primary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (recipient == null) {
                                item(key = "recipient_needed") {
                                    OreText(text = PassCopy.RECIPIENT_NEEDED, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
                                }
                            }
                        }
                    }
                    items(PassCopy.PRODUCTS, key = { "product_${it.id}" }) { product ->
                        PassProductCard(
                            product = product,
                            seatsRemaining = if (product.prestige) state.prestigeSeatsRemaining else null,
                            actionLabel = when {
                                gifting -> PassCopy.GIFT
                                expiresAt != null && !product.prestige -> PassCopy.EXTEND
                                else -> PassCopy.GET
                            },
                            actionEnabled = !gifting || recipient != null,
                            onOption = { option ->
                                if (gifting) recipient?.let { onGift(product.id, option.id, it.userId) }
                                else onPurchase(product.id, option.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PassProductCard(
    product: PassProduct,
    seatsRemaining: Int?,
    actionLabel: String,
    actionEnabled: Boolean,
    onOption: (PassOption) -> Unit,
) {
    val space = OreTheme.space
    PassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = roleLabel(product.role),
                style = OreTheme.type.titleSmall,
                color = roleColor(product.role),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreText(text = PassCopy.GRANTS, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary, maxLines = 1)
            RoleBadge(role = product.role)
        }
        if (product.includes != null) {
            OreText(text = product.includes, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
        }
        if (seatsRemaining != null) {
            OreText(
                text = "$seatsRemaining ${PassCopy.SEATS_SUFFIX}",
                style = OreTheme.type.caption,
                color = OreTheme.color.text.secondary,
                maxLines = 1,
            )
        }
        product.options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.xxs)) {
                    OreText(text = option.price, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary, maxLines = 1)
                    if (option.ore != null) {
                        OreText(
                            text = "${PassCopy.ORE_PREFIX} ${option.ore}",
                            style = OreTheme.type.caption,
                            color = OreTheme.color.text.tertiary,
                            maxLines = 1,
                        )
                    }
                }
                OreGhostButton(text = actionLabel, onClick = { onOption(option) }, enabled = actionEnabled)
            }
        }
    }
}

@Composable
private fun PassModeTab(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(OreTheme.size.touchTarget)
            .background(if (selected) OreTheme.color.accent.subtle else OreTheme.color.surface.raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OreText(
            text = text,
            style = OreTheme.type.label,
            color = when {
                !enabled -> OreTheme.color.text.disabled
                selected -> OreTheme.color.accent.onSubtle
                else -> OreTheme.color.text.secondary
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PassOverline(text: String) {
    OreText(text = text, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
}

@Composable
private fun PassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}
