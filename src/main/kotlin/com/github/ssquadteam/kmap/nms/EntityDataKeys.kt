package com.github.ssquadteam.kmap.nms

import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionfc
import org.joml.Vector3fc

@Suppress("UNCHECKED_CAST")
object EntityDataKeys {
    private fun <T : Any> field(owner: Class<*>, name: String): EntityDataAccessor<T> {
        val f = owner.getDeclaredField(name)
        f.isAccessible = true
        return f.get(null) as EntityDataAccessor<T>
    }

    val SHARED_FLAGS: EntityDataAccessor<Byte> = field(Entity::class.java, "DATA_SHARED_FLAGS_ID")
    val INTERPOLATION_START: EntityDataAccessor<Int> = field(Display::class.java, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
    val INTERPOLATION_DURATION: EntityDataAccessor<Int> = field(Display::class.java, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID")
    val TELEPORT_DURATION: EntityDataAccessor<Int> = field(Display::class.java, "DATA_POS_ROT_INTERPOLATION_DURATION_ID")
    val TRANSLATION: EntityDataAccessor<Vector3fc> = field(Display::class.java, "DATA_TRANSLATION_ID")
    val SCALE: EntityDataAccessor<Vector3fc> = field(Display::class.java, "DATA_SCALE_ID")
    val LEFT_ROTATION: EntityDataAccessor<Quaternionfc> = field(Display::class.java, "DATA_LEFT_ROTATION_ID")
    val RIGHT_ROTATION: EntityDataAccessor<Quaternionfc> = field(Display::class.java, "DATA_RIGHT_ROTATION_ID")
    val BILLBOARD: EntityDataAccessor<Byte> = field(Display::class.java, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    val BRIGHTNESS: EntityDataAccessor<Int> = field(Display::class.java, "DATA_BRIGHTNESS_OVERRIDE_ID")
    val VIEW_RANGE: EntityDataAccessor<Float> = field(Display::class.java, "DATA_VIEW_RANGE_ID")
    val WIDTH: EntityDataAccessor<Float> = field(Display::class.java, "DATA_WIDTH_ID")
    val HEIGHT: EntityDataAccessor<Float> = field(Display::class.java, "DATA_HEIGHT_ID")
    val TEXT: EntityDataAccessor<Component> = field(Display.TextDisplay::class.java, "DATA_TEXT_ID")
    val LINE_WIDTH: EntityDataAccessor<Int> = field(Display.TextDisplay::class.java, "DATA_LINE_WIDTH_ID")
    val BACKGROUND: EntityDataAccessor<Int> = field(Display.TextDisplay::class.java, "DATA_BACKGROUND_COLOR_ID")
    val TEXT_OPACITY: EntityDataAccessor<Byte> = field(Display.TextDisplay::class.java, "DATA_TEXT_OPACITY_ID")
    val TEXT_FLAGS: EntityDataAccessor<Byte> = field(Display.TextDisplay::class.java, "DATA_STYLE_FLAGS_ID")
    val FRAME_ITEM: EntityDataAccessor<ItemStack> = field(ItemFrame::class.java, "DATA_ITEM")
    val FRAME_ROTATION: EntityDataAccessor<Int> = field(ItemFrame::class.java, "DATA_ROTATION")
    val HANGING_DIRECTION: EntityDataAccessor<Direction> = field(HangingEntity::class.java, "DATA_DIRECTION")
}
