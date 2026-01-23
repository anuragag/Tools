package com.slackvoice.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slackvoice.assistant.data.model.ChannelSummary
import com.slackvoice.assistant.ui.theme.SlackGreen
import com.slackvoice.assistant.ui.theme.SlackYellow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChannelSummaryCard(
    summary: ChannelSummary,
    isCurrentChannel: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentChannel) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrentChannel) 4.dp else 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Channel icon
                    Icon(
                        imageVector = if (summary.channel.isPrivate) {
                            Icons.Default.Lock
                        } else {
                            Icons.Default.Tag
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Channel name
                    Text(
                        text = summary.channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Mention badge
                if (summary.hasUnreadMentions) {
                    Badge(
                        containerColor = SlackYellow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Text("@", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Message count
                StatChip(
                    icon = Icons.Default.Message,
                    value = summary.messageCount.toString(),
                    label = "messages"
                )

                // Participants
                StatChip(
                    icon = Icons.Default.People,
                    value = summary.participants.size.toString(),
                    label = "people"
                )

                // Threads
                if (summary.threadCount > 0) {
                    StatChip(
                        icon = Icons.Default.Forum,
                        value = summary.threadCount.toString(),
                        label = "threads"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Summary text
            Text(
                text = summary.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Last activity time
            summary.lastActivityTime?.let { time ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last activity: ${formatTime(time)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Participants preview
            if (summary.participants.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-8).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    summary.participants.take(5).forEachIndexed { index, user ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(getAvatarColor(index)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user.displayableName().take(1).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    if (summary.participants.size > 5) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "+${summary.participants.size - 5} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun getAvatarColor(index: Int): androidx.compose.ui.graphics.Color {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        SlackGreen,
        MaterialTheme.colorScheme.tertiary,
        SlackYellow,
        MaterialTheme.colorScheme.secondary
    )
    return colors[index % colors.size]
}

private fun formatTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
