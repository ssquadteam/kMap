#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;
flat out int kmMode;
flat out int kmClipKind;
flat out vec4 kmClip;
flat out vec4 kmAux;
flat out vec2 kmCursor;
out vec2 kmCanvas;
out vec2 kmQuad;

/*KMAP_DEFINES*/

const float KM_K = 100.0;
const float KM_BASE = 1000000.0;
const float KM_STEP = 200000.0;
const float KM_PARAM = 1000.0;

const int KM_CHD_D[97] = int[](4,2,29,8,40,14,34,64,1,4,0,0,35,83,7,5,21,24,2,8,0,1,14,40,0,0,1,9,4,65,1,1,0,3,0,26,20,27,51,7,0,0,60,18,0,42,9,10,0,15,2,59,13,13,0,1,8,19,19,3,18,2,27,10,46,0,48,39,9,0,8,1,0,12,5,3,26,6,39,0,0,3,12,14,34,9,75,31,1,0,69,2,3,14,0,0,7);
const int KM_CHD_T[256] = int[](183,105,161,178,214,188,20,136,22,201,94,98,130,75,59,179,117,133,97,245,158,103,207,71,154,193,-1,-1,-1,-1,36,-1,206,221,-1,64,226,82,48,24,45,8,37,68,66,17,88,137,81,171,89,90,147,166,229,182,76,102,6,243,63,32,15,61,54,223,13,91,114,109,167,55,11,240,213,9,184,195,132,35,157,141,25,152,123,50,224,56,146,74,208,52,28,116,53,16,191,194,244,99,225,203,120,138,124,140,128,47,40,233,84,34,168,62,237,202,85,111,41,220,23,172,49,43,142,150,185,164,149,78,131,151,231,4,126,134,232,107,83,121,144,210,187,211,230,181,21,73,235,205,80,186,104,156,100,227,12,180,129,198,26,215,38,95,70,234,196,110,77,143,216,222,-1,135,-1,119,5,204,39,106,118,-1,247,-1,72,87,93,-1,239,122,242,127,96,51,112,241,57,246,200,115,176,10,212,159,31,238,209,155,27,190,139,46,153,236,108,65,192,60,228,145,29,174,-1,170,42,163,19,92,113,177,199,86,219,160,217,79,58,14,148,30,67,197,165,18,162,189,7,125,44,169,173,33,101,69,218,175);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
int kmPaletteId(vec4 c) {
    ivec3 v = ivec3(round(c.rgb * 255.0));
    int h1 = (v.r * 31 + v.g * 7 + v.b * 3) % 97;
    int h2 = v.r * 13 + v.g * 101 + v.b * 57;
    return KM_CHD_T[(h2 + KM_CHD_D[h1]) % 256];
}

const int KM_GLOW_LIGHT = 210;
ivec2 kmTileBase = ivec2(0);

int kmDigit(ivec2 p) {
    return kmPaletteId(texelFetch(Sampler0, kmTileBase + p, 0)) - 4;
}

bool kmIsTile() {
    return kmDigit(ivec2(0, 0)) == 107 && kmDigit(ivec2(1, 0)) == 77;
}

int kmDigits(int start, int count) {
    int v = 0;
    for (int i = 0; i < count; i++) {
        v = v * 128 + kmDigit(ivec2(start + i, 0));
    }
    return v;
}
#endif

float kmNowTicks(int band) {
    return GameTime * 24000.0 - float(band) * 6000.0;
}

float kmAnimEase(int start, int band) {
    float elapsed = mod(kmNowTicks(band) - float(start), 128.0);
    float t = clamp(elapsed / KM_ANIM_TICKS, 0.0, 1.0);
    float r = 1.0 - t;
    return 1.0 - r * r * r;
}

int kmBand() {
    return int(clamp(floor(GameTime * 4.0), 0.0, 3.0));
}

float kmUiScale() {
    return min(ScreenSize.x / KM_CANVAS_W, ScreenSize.y / KM_CANVAS_H);
}

vec2 kmCanvasOrigin() {
    return floor((ScreenSize - vec2(KM_CANVAS_W, KM_CANVAS_H) * kmUiScale()) * 0.5);
}

vec2 kmScreenToCanvas(vec2 px) {
    return (px - kmCanvasOrigin()) / kmUiScale();
}

vec4 kmCanvasToClip(vec2 canvas, float depth) {
    vec2 px = kmCanvasOrigin() + canvas * kmUiScale();
    vec2 ndc = px / ScreenSize * 2.0 - 1.0;
    return vec4(ndc.x, -ndc.y, depth, 1.0);
}

vec4 kmMiniRect(int param) {
    int corner = param & 3;
    float size = KM_MINI_SIZE;
    float margin = KM_MINI_MARGIN;
    vec2 screenCanvas = kmScreenToCanvas(vec2(0.0));
    vec2 screenCanvasMax = kmScreenToCanvas(ScreenSize);
    float x = (corner == 1 || corner == 3) ? screenCanvasMax.x - margin - size : screenCanvas.x + margin;
    float y = (corner >= 2) ? screenCanvasMax.y - margin - size - KM_MINI_PLATE : screenCanvas.y + margin;
    return vec4(x, y, size, size);
}

vec4 kmBigRect() {
    float size = KM_BIG_SIZE;
    return vec4((KM_CANVAS_W - size) * 0.5, (KM_CANVAS_H - size) * 0.5, size, size);
}

float kmZoom(int idx) {
    return KM_ZOOMS[clamp(idx, 0, 6)];
}

vec2 kmCameraXZ() {
    return vec2(CameraBlockPos.xz) - CameraOffset.xz;
}

vec2 kmViewAngles() {
    mat4 m = ProjMat * ModelViewMat;
    vec3 f = normalize(vec3(m[0][3], m[1][3], m[2][3]));
    float pitch = degrees(-asin(clamp(f.y, -1.0, 1.0)));
    float yaw = degrees(atan(-f.x, f.z));
    return vec2(yaw, pitch);
}

vec2 kmCursorPos(float sens) {
    vec2 a = kmViewAngles();
    vec2 c = vec2(KM_CANVAS_W, KM_CANVAS_H) * 0.5 + a * KM_CURSOR_K * sens;
    return clamp(c, vec2(0.0), vec2(KM_CANVAS_W, KM_CANVAS_H));
}

float kmGutter() {
    return max(0.0, -kmScreenToCanvas(vec2(0.0)).x);
}

vec2 kmCursorShown(vec2 c) {
    if (c.x < KM_EDGE) {
        c.x -= kmGutter();
    } else if (c.x > KM_CANVAS_W - KM_EDGE) {
        c.x += kmGutter();
    }
    return c;
}

float kmSens(int param) {
    return 1.0 + float(param & 31) * 0.1;
}

vec3 kmTint(int idx) {
    return KM_TINTS[clamp(idx, 0, 63)];
}

vec2 kmCornerUnit(int corner) {
    vec2 f = fract(UV0 * 256.0);
    return vec2(f.x > 0.5 ? 1.0 : 0.0, f.y > 0.5 ? 1.0 : 0.0);
}

bool kmSurfaceVisible(int surface, int band) {
    if (surface == 0) return band == 0;
    if (surface == 1) return band == 1;
    if (surface == 2 || surface == 3) return band >= 2;
    if (surface == 4) return band == 2;
    if (surface == 5) return band <= 1;
    return true;
}

float kmDepth(int surface, int layer) {
    return 0.93 + float(surface) * 0.008 + float(layer) * 0.0015;
}

void kmHide() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    kmMode = 0;
}

vec2 kmFontCorner() {
    vec2 f = fract(UV0 * 256.0);
    return vec2(f.x > 0.5 ? 1.0 : 0.0, f.y > 0.5 ? 1.0 : 0.0);
}

ivec2 kmCornerTexel(vec2 uv, int corner, ivec2 size) {
    vec2 p = uv * vec2(size);
    vec2 u = kmFontCorner();
    return ivec2(u.x > 0.5 ? int(ceil(p.x - 0.25)) - 1 : int(floor(p.x + 0.25)),
                 u.y > 0.5 ? int(ceil(p.y - 0.25)) - 1 : int(floor(p.y + 0.25)));
}

bool kmIsMagic(vec4 t) {
    ivec3 v = ivec3(round(t.rgb * 255.0));
    return v.r == 107 && v.g == 77;
}

void kmPassFog() {
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = 0.0;
    cylindricalVertexDistance = 0.0;
#endif
}

void kmClassA(int surface, float localX, float localY, int param, int band) {
    int corner = 0;
    if (!kmSurfaceVisible(surface, band)) {
        kmHide();
        return;
    }
    int c = int(round(Color.r * 255.0)) << 16 | int(round(Color.g * 255.0)) << 8 | int(round(Color.b * 255.0));
    int kind = (c >> 20) & 15;
    vec2 origin = vec2(0.0);
    vec4 area = vec4(0.0, 0.0, KM_CANVAS_W, KM_CANVAS_H);
    if (surface == 0) {
        area = kmMiniRect(param);
        origin = area.xy;
        if ((param & 4) != 0) {
            kmClipKind = 2;
        } else {
            kmClipKind = 1;
        }
        kmClip = area;
    } else if (surface == 1) {
        area = kmBigRect();
        origin = area.xy;
        kmClipKind = 1;
        kmClip = area;
    }
    kmQuad = kmCornerUnit(corner);
    vertexColor = vec4(1.0);

    if (kind == 0 || kind == 1 || kind == 5) {
        float y = float(c & 511) - 128.0;
        int tint = (c >> 9) & 63;
        int layer = (c >> 15) & 3;
        int fx = (c >> 17) & 7;
        vec2 canvas = origin + vec2(localX - 1.0, y + localY + 2.0);
        if (fx == 1 && surface == 2) {
            canvas += kmCursorPos(kmSens(param)) - vec2(KM_CANVAS_W, KM_CANVAS_H) * 0.5;
        }
        if (kind == 1) {
            canvas.x += canvas.x < KM_CANVAS_W * 0.5 ? -kmGutter() : kmGutter();
        }
        if (kind == 0 && surface <= 1 && fx != 4) {
            kmClipKind = 0;
        }
        if (fx >= 5) {
            kmClipKind = 1;
            kmClip = KM_CLIPS[fx - 5];
        }
        kmCanvas = canvas;
        if (tint != 0) {
            vertexColor = vec4(kmTint(tint), 1.0);
        }
        kmAux = vec4(float(fx), float(tint), 0.0, 0.0);
        if (fx == 2 || fx == 3) {
            kmCursor = kmCursorShown(kmCursorPos(kmSens(param)));
        }
        if (surface == 2 && ((param >> 12) & 1) == 1) {
            vertexColor.a = smoothstep(0.55, 1.0, kmAnimEase((param >> 5) & 127, band));
        }
        gl_Position = kmCanvasToClip(canvas, kmDepth(surface, layer));
        kmMode = 1;
        return;
    }

    if (kind == 2) {
        float y = float(c & 511) - 128.0;
        float angle;
        if (surface <= 1) {
            angle = radians(kmViewAngles().x + 180.0);
        } else {
            angle = radians(float((c >> 9) & 255) * 360.0 / 256.0);
        }
        vec2 d = (kmCornerUnit(corner) - 0.5) * KM_ARROW_PX;
        vec2 canvas = origin + vec2(localX - 1.0, y + localY + 2.0);
        vec2 center = surface <= 1 ? area.xy + area.zw * 0.5 : canvas - d;
        float cs = cos(angle);
        float sn = sin(angle);
        canvas = center + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
        if (((c >> 17) & 1) == 1 && surface == 2) {
            canvas += kmCursorPos(kmSens(param)) - vec2(KM_CANVAS_W, KM_CANVAS_H) * 0.5;
        }
        kmCanvas = canvas;
        kmClipKind = 0;
        if (surface == 2 && ((param >> 12) & 1) == 1) {
            vertexColor.a = smoothstep(0.55, 1.0, kmAnimEase((param >> 5) & 127, band));
        }
        gl_Position = kmCanvasToClip(canvas, kmDepth(surface, 3) + 0.0005);
        kmMode = 1;
        return;
    }

    if (kind == 3) {
        int tint = c & 63;
        vec2 u = kmCornerUnit(corner);
        vec2 canvasMin = kmScreenToCanvas(vec2(0.0));
        vec2 canvasMax = kmScreenToCanvas(ScreenSize);
        vec2 canvas = mix(canvasMin, canvasMax, u);
        if (surface <= 1) {
            canvas = area.xy + u * area.zw;
        }
        texCoord0 = UV0;
        vertexColor = vec4(kmTint(tint), 1.0);
        kmCanvas = canvas;
        gl_Position = kmCanvasToClip(canvas, kmDepth(surface, 0) - 0.001);
        kmMode = 4;
        return;
    }

    if (kind == 4) {
        vec2 cur = kmCursorShown(kmCursorPos(kmSens(param)));
        vec2 canvas = cur + kmCornerUnit(corner) * KM_CURSOR_PX - vec2(KM_CURSOR_HOT_X, KM_CURSOR_HOT_Y);
        kmCanvas = canvas;
        gl_Position = kmCanvasToClip(canvas, 0.995);
        kmMode = 1;
        return;
    }

    kmHide();
    return;
}

void main() {
    kmMode = 0;
    kmClipKind = 0;
    kmClip = vec4(0.0);
    kmAux = vec4(0.0);
    kmCursor = vec2(-1000.0);
    kmCanvas = vec2(0.0);
    kmQuad = vec2(0.0);
    texCoord0 = UV0;
    int corner = 0;

#if !defined(IS_GUI)
    if (ivec3(round(Color.rgb * 255.0)) == KM_SHADER_ONLY) {
        kmHide();
        return;
    }
#endif
    int band = kmBand();

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (Position.x > KM_BASE - 100000.0) {
        kmPassFog();
        float rel = Position.x - KM_BASE;
        int surface = int(floor((rel + 50000.0) / KM_STEP));
        float localX = (rel - float(surface) * KM_STEP) / KM_K;
        float localY = -Position.y / KM_K;
        int param = int(round(Position.z / KM_PARAM));
        kmClassA(surface, localX, localY, param, band);
        return;
    }

    ivec2 texSize = textureSize(Sampler0, 0);
    vec2 u = UV0;
    bool tile = false;
    if (texSize == ivec2(128, 128)) {
        tile = kmIsTile();
    } else if (texSize.x > 128 && (texSize.x & 127) == 0 && (texSize.y & 127) == 0 && max(abs(Position.x), abs(Position.z)) < 1.5) {
        vec2 texel = UV0 * vec2(texSize);
        vec2 grid = round(texel / 128.0) * 128.0;
        if (all(lessThan(abs(texel - grid), vec2(0.01)))) {
            u = vec2(Position.x > 0.0 ? 1.0 : 0.0, (Position.z > 0.0) == (UV2.x == KM_GLOW_LIGHT) ? 1.0 : 0.0);
            kmTileBase = ivec2(grid) - ivec2(u) * 128;
            tile = all(greaterThanEqual(kmTileBase, ivec2(0))) && kmIsTile() && kmDigit(ivec2(2, 0)) <= 3;
        }
    }
    if (tile) {
        kmPassFog();
        int tkind = kmDigit(ivec2(2, 0));
        ivec2 origin = ivec2(kmDigits(3, 4), kmDigits(7, 4)) - 134217728;
        int flags = kmDigit(ivec2(11, 0));
        int mini = kmDigit(ivec2(12, 0));
        ivec2 panQ = ivec2(kmDigits(13, 4), kmDigits(17, 4)) - 134217728;
        int screen = kmDigit(ivec2(21, 0));
        int sensP = kmDigit(ivec2(22, 0));
        float blocksWide = tkind == 1 ? 64.0 : 128.0;
        float blocksTall = 128.0;
        if (tkind == 3) {
            float lodScale = exp2(float(kmDigit(ivec2(23, 0))));
            blocksWide = 128.0 * lodScale;
            blocksTall = 128.0 * lodScale;
        }
        vec2 span = u * vec2(blocksWide, blocksTall);
        vec2 canvas;
        int surface;
        vec4 area;
        if (band <= 1) {
            if ((flags & 1) == 0 || (band == 1 && (flags & 4) == 0)) {
                kmHide();
                return;
            }
            surface = band;
            area = band == 0 ? kmMiniRect(mini) : kmBigRect();
            float zoom = kmZoom((mini >> 3) & 7);
            float blocksAcross = (band == 0 ? KM_MINI_BLOCKS : KM_BIG_BLOCKS) / zoom;
            vec2 rel = vec2(origin - CameraBlockPos.xz) + CameraOffset.xz + span;
            canvas = area.xy + area.zw * 0.5 + rel * (area.z / blocksAcross);
            kmClipKind = (band == 0 && (mini & 4) != 0) ? 2 : 1;
            kmClip = area;
        } else {
            if ((flags & 2) == 0) {
                kmHide();
                return;
            }
            surface = 2;
            float scale = KM_SCREEN_ZOOMS[clamp(screen & 15, 0, 11)];
            vec2 rel = vec2(origin * 4 - panQ) * 0.25 + span;
            canvas = vec2(KM_CANVAS_W, KM_CANVAS_H) * 0.5 + rel * scale;
            if ((screen & 16) != 0) {
                canvas += kmCursorPos(kmSens(sensP)) - vec2(KM_CANVAS_W, KM_CANVAS_H) * 0.5;
            }
            float lnStart = float(kmDigits(24, 2)) / 2048.0 - 4.0;
            vec2 slide = (vec2(kmDigits(31, 2), kmDigits(33, 2)) - 8192.0) * 0.5;
            if (abs(lnStart) > 0.0005 || slide != vec2(0.0)) {
                vec2 anchor = (vec2(kmDigits(26, 2), kmDigits(28, 2)) - 4096.0) * 0.5;
                float e = kmAnimEase(kmDigit(ivec2(30, 0)), band);
                canvas = anchor + (canvas - anchor) * exp(lnStart * (1.0 - e)) + slide * (1.0 - e);
            }
            kmClipKind = 0;
        }
        texCoord0 = UV0;
        kmCanvas = canvas;
        kmAux = vec4(float(tkind), float(origin.x & 4095), float(origin.y & 4095), float((kmTileBase.x >> 7) * 256 + (kmTileBase.y >> 7)));
        vertexColor = vec4(1.0);
        float layerDepth = (flags & 8) != 0 ? 0.0001 : (tkind == 3 ? 0.0003 : 0.0006);
        gl_Position = kmCanvasToClip(canvas, kmDepth(surface, 0) + layerDepth);
        kmMode = 2;
        return;
    }

    if (texSize.x > 64) {
        ivec2 ct = kmCornerTexel(UV0, corner, texSize);
        vec4 magic = texelFetch(Sampler0, ct, 0);
        int mb = int(round(magic.b * 255.0));
        if (kmIsMagic(magic) && magic.a < 0.1 && mb >= 32 && mb < 40) {
            kmPassFog();
            int c = int(round(Color.r * 255.0)) << 16 | int(round(Color.g * 255.0)) << 8 | int(round(Color.b * 255.0));
            int kind = (c >> 20) & 15;
            vec2 u = kmCornerUnit(corner);
            if (kind == 8) {
                if (band > 1) {
                    kmHide();
                    return;
                }
                int mini = c & 63;
                float size = float((c >> 6) & 63) + 1.0;
                int clampEdge = (c >> 12) & 1;
                int layer = (c >> 13) & 3;
                int showBig = (c >> 15) & 1;
                int itint = (c >> 16) & 15;
                if (band == 1 && showBig == 0) {
                    kmHide();
                    return;
                }
                vec4 area = band == 0 ? kmMiniRect(mini) : kmBigRect();
                float zoom = kmZoom((mini >> 3) & 7);
                float blocksAcross = (band == 0 ? KM_MINI_BLOCKS : KM_BIG_BLOCKS) / zoom;
                vec2 rel = Position.xz * (area.z / blocksAcross);
                vec2 center = area.xy + area.zw * 0.5;
                if (band == 0 && (mini & 4) != 0) {
                    float r = area.z * 0.5 - size * 0.25;
                    float len = length(rel);
                    if (len > r) {
                        if (clampEdge == 0) {
                            kmHide();
                            return;
                        }
                        rel = rel / len * r;
                    }
                } else {
                    vec2 lim = area.zw * 0.5 - size * 0.25;
                    if (any(greaterThan(abs(rel), lim))) {
                        if (clampEdge == 0) {
                            kmHide();
                            return;
                        }
                        rel = clamp(rel, -lim, lim);
                    }
                }
                vec2 canvas = center + rel + (u - 0.5) * size;
                kmCanvas = canvas;
                kmClipKind = 0;
                vertexColor = itint == 0 ? vec4(1.0) : vec4(kmTint(itint), 1.0);
                gl_Position = kmCanvasToClip(canvas, kmDepth(band, 2 + (layer & 1)) + float(layer) * 0.0002);
                kmMode = 5;
                return;
            }
            if (kind == 9) {
                if (band >= 2) {
                    kmHide();
                    return;
                }
                vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);
                if (clip.w <= 0.05) {
                    kmHide();
                    return;
                }
                int dx = (c & 255) - 128;
                int row = (c >> 8) & 3;
                int reveal = (c >> 10) & 1;
                int tint = (c >> 11) & 63;
                int variant = (c >> 17) & 7;
                vec2 ndc = clip.xy / clip.w;
                vec2 px = (ndc * vec2(0.5, -0.5) + 0.5) * ScreenSize;
                vec2 canvasAnchor = kmScreenToCanvas(px);
                if (reveal == 1) {
                    vec2 centerCanvas = kmScreenToCanvas(ScreenSize * 0.5);
                    if (distance(canvasAnchor, centerCanvas) > KM_REVEAL_RADIUS) {
                        kmHide();
                        return;
                    }
                }
                vec2 cell = variant == 0 ? vec2(KM_WICON_PX) : vec2(KM_WCELL_W, KM_WCELL_H);
                vec2 offset = variant == 0 || variant == 2 ? -cell * 0.5 : vec2(float(dx), KM_WICON_PX * 0.5 + 2.0 + float(row) * (KM_WCELL_H + 1.0));
                vec2 canvas = canvasAnchor + offset + u * cell;
                kmCanvas = canvas;
                vertexColor = tint == 0 ? vec4(1.0) : vec4(kmTint(tint), 1.0);
                gl_Position = kmCanvasToClip(canvas, variant == 2 ? 0.901 : 0.9);
                kmMode = 6;
                return;
            }
        }
    }
#elif defined(IS_GUI)
    if (band == 3) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vertexColor = vec4(0.0);
        return;
    }
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
#if defined(IS_SEE_THROUGH) && !defined(IS_GUI)
    if (band >= 2) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    }
#endif
}
