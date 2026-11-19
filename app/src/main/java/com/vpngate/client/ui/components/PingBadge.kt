package com.vpngate.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpngate.client.ui.theme.GreenPing
import com.vpngate.client.ui.theme.GreyPing
import com.vpngate.client.ui.theme.OrangePing
import com.vpngate.client.ui.theme.RedPing
import com.vpngate.client.ui.theme.YellowPing

@Composable
fun PingBadge(pingMs: Int?, modifier: Modifier = Modifier) {
    val (bgColor, textColor, text) = when {
        pingMs == null -> Triple(GreyPing.copy(alpha = 0.2f), GreyPing, "-")
        pingMs < 0 -> Triple(RedPing.copy(alpha = 0.2f), RedPing, "Unreachable")
        pingMs < 100 -> Triple(GreenPing.copy(alpha = 0.2f), GreenPing, "${pingMs}ms")
        pingMs < 250 -> Triple(YellowPing.copy(alpha = 0.2f), YellowPing, "${pingMs}ms")
        pingMs < 500 -> Triple(OrangePing.copy(alpha = 0.2f), OrangePing, "${pingMs}ms")
        else -> Triple(RedPing.copy(alpha = 0.2f), RedPing, "${pingMs}ms")
    }

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
