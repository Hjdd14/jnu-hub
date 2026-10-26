package cn.jnu.hub.ui.theme

import androidx.compose.ui.unit.dp

/** 统一间距与尺寸 token，禁止在业务代码里写魔法数字 */
object Dimens {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val cardSpacing = 12.dp
    val sectionSpacing = 20.dp

    /** 最小可点击区域（无障碍要求 48dp） */
    val minTouchTarget = 48.dp
    val iconSm = 18.dp
    val iconMd = 24.dp
    val iconLg = 32.dp
    val bottomBarHeight = 64.dp
}
