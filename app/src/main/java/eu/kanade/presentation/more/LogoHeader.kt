package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun LogoHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // KMK -->
        // 「更多」页顶部 logo：直接用应用图标里那本书（蓝书页 + 白斜光带 + 白折角）。
        // 必须用 Image() 而不是 Icon(tint=...)：tint 会把三层涂成同一种颜色，光带就没了。
        Image(
            painter = painterResource(R.drawable.ic_lumen_logo),
            contentDescription = null,
            modifier = Modifier
                .padding(vertical = 40.dp)
                .size(128.dp),
        )
        // KMK <--

        HorizontalDivider()
    }
}
