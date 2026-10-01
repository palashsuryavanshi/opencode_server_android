package com.palash.opencodeserver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palash.opencodeserver.model.ServerEndpoint
import com.palash.opencodeserver.ui.theme.*

@Composable
fun EndpointsScreen(
    endpoints: List<ServerEndpoint>,
    selectedEndpoint: ServerEndpoint?,
    testResult: String?,
    onTestEndpoint: (ServerEndpoint) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedEndpointId by remember { mutableStateOf<String?>("ep-1") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DevBackground)
            .padding(horizontal = 16.dp)
            .testTag("endpoints_screen")
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Info Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(10.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = "LOCAL DAEMON API & SOCKET ROUTES",
                    fontSize = 11.sp,
                    fontFamily = DevMonospace,
                    fontWeight = FontWeight.Bold,
                    color = DevTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Direct HTTP/REST and WebSocket interfaces exposed by 127.0.0.1:4096",
                    style = Typography.bodyMedium,
                    color = DevTextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(endpoints) { ep ->
                val isExpanded = expandedEndpointId == ep.id
                val (methodBg, methodColor) = when (ep.method) {
                    "GET" -> DevAccentCyanSubtle to DevAccentCyan
                    "POST" -> DevAccentEmeraldSubtle to DevAccentEmerald
                    "WS" -> DevAccentPurple.copy(alpha = 0.2f) to DevAccentPurple
                    else -> DevSurfaceElevated to DevTextSecondary
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = DevSurface),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isExpanded) DevAccentCyan.copy(alpha = 0.4f) else DevBorder,
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedEndpointId = if (isExpanded) null else ep.id
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(methodBg)
                                        .border(1.dp, methodColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = ep.method,
                                        fontFamily = DevMonospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = methodColor
                                    )
                                }

                                Column {
                                    Text(
                                        text = ep.path,
                                        fontFamily = DevMonospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DevTextPrimary
                                    )
                                    Text(
                                        text = ep.description,
                                        style = Typography.bodySmall,
                                        color = DevTextSecondary
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = DevTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DevCodeBackground)
                                    .border(1.dp, DevBorder)
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(DevAccentEmeraldSubtle)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${ep.status} OK",
                                                fontFamily = DevMonospace,
                                                fontSize = 11.sp,
                                                color = DevTerminalGreen
                                            )
                                        }

                                        Text(
                                            text = "${ep.latencyMs}ms latency",
                                            fontFamily = DevMonospace,
                                            fontSize = 11.sp,
                                            color = DevTextMuted
                                        )
                                    }

                                    Button(
                                        onClick = { onTestEndpoint(ep) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = DevAccentCyan,
                                            contentColor = DevBackground
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Send Probe", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "RESPONSE PAYLOAD",
                                    fontSize = 10.sp,
                                    fontFamily = DevMonospace,
                                    color = DevTextMuted
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(DevSurface)
                                        .border(1.dp, DevBorder, RoundedCornerShape(6.dp))
                                        .horizontalScroll(rememberScrollState())
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = ep.responseBody,
                                        fontFamily = DevMonospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = DevTerminalGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
