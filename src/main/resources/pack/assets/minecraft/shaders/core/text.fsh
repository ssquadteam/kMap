#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
flat in int kmMode;
flat in int kmClipKind;
flat in vec4 kmClip;
flat in vec4 kmTexRect;
flat in vec4 kmAux;
flat in vec2 kmCursor;
in vec2 kmCanvas;
in vec2 kmQuad;

out vec4 fragColor;

/*KMAP_FDEFINES*/

const int KM_CHD_D[97] = int[](4,2,29,8,40,14,34,64,1,4,0,0,35,83,7,5,21,24,2,8,0,1,14,40,0,0,1,9,4,65,1,1,0,3,0,26,20,27,51,7,0,0,60,18,0,42,9,10,0,15,2,59,13,13,0,1,8,19,19,3,18,2,27,10,46,0,48,39,9,0,8,1,0,12,5,3,26,6,39,0,0,3,12,14,34,9,75,31,1,0,69,2,3,14,0,0,7);
const int KM_CHD_T[256] = int[](183,105,161,178,214,188,20,136,22,201,94,98,130,75,59,179,117,133,97,245,158,103,207,71,154,193,-1,-1,-1,-1,36,-1,206,221,-1,64,226,82,48,24,45,8,37,68,66,17,88,137,81,171,89,90,147,166,229,182,76,102,6,243,63,32,15,61,54,223,13,91,114,109,167,55,11,240,213,9,184,195,132,35,157,141,25,152,123,50,224,56,146,74,208,52,28,116,53,16,191,194,244,99,225,203,120,138,124,140,128,47,40,233,84,34,168,62,237,202,85,111,41,220,23,172,49,43,142,150,185,164,149,78,131,151,231,4,126,134,232,107,83,121,144,210,187,211,230,181,21,73,235,205,80,186,104,156,100,227,12,180,129,198,26,215,38,95,70,234,196,110,77,143,216,222,-1,135,-1,119,5,204,39,106,118,-1,247,-1,72,87,93,-1,239,122,242,127,96,51,112,241,57,246,200,115,176,10,212,159,31,238,209,155,27,190,139,46,153,236,108,65,192,60,228,145,29,174,-1,170,42,163,19,92,113,177,199,86,219,160,217,79,58,14,148,30,67,197,165,18,162,189,7,125,44,169,173,33,101,69,218,175);

int kmPaletteId(vec4 c) {
    ivec3 v = ivec3(round(c.rgb * 255.0));
    int h1 = (v.r * 31 + v.g * 7 + v.b * 3) % 97;
    int h2 = v.r * 13 + v.g * 101 + v.b * 57;
    return KM_CHD_T[(h2 + KM_CHD_D[h1]) % 256];
}

bool kmClipped() {
    if (kmClipKind == 1) {
        return kmCanvas.x < kmClip.x || kmCanvas.y < kmClip.y || kmCanvas.x > kmClip.x + kmClip.z || kmCanvas.y > kmClip.y + kmClip.w;
    }
    if (kmClipKind == 2) {
        vec2 c = kmClip.xy + kmClip.zw * 0.5;
        return distance(kmCanvas, c) > kmClip.z * 0.5;
    }
    return false;
}

vec4 kmSampleTile() {
    ivec2 size = textureSize(Sampler0, 0);
    int tkind = int(kmAux.x + 0.5);
    vec2 p = clamp(texCoord0, vec2(0.0), vec2(0.99999)) * vec2(size);
    ivec2 t = ivec2(p);
    if (tkind == 1 || tkind == 4) {
        ivec2 a = ivec2((t.x / 2) * 2, t.y);
        if (a.y == 0 && a.x < 24) {
            return vec4(0.0);
        }
        vec4 ca = texelFetch(Sampler0, a, 0);
        vec4 cb = texelFetch(Sampler0, a + ivec2(1, 0), 0);
        if (ca.a < 0.5 || cb.a < 0.5) {
            return vec4(0.0);
        }
        int v = (kmPaletteId(ca) - 4) * 240 + (kmPaletteId(cb) - 4);
        if (v < 0 || v >= 32768) {
            return vec4(0.0);
        }
        vec3 rgb = vec3(float((v >> 10) & 31), float((v >> 5) & 31), float(v & 31)) / 31.0;
        return vec4(rgb, 1.0);
    }
    if (t.y == 0 && t.x < 24) {
        return vec4(0.0);
    }
    vec4 c = texelFetch(Sampler0, t, 0);
    if (tkind == 2) {
        if (c.a < 0.5) {
            return vec4(0.0);
        }
        int id = kmPaletteId(c);
        return id == 119 ? vec4(KM_FOG_KNOWN, 1.0) : vec4(KM_FOG_UNKNOWN, 1.0);
    }
    return c;
}

void main() {
    if (kmMode != 0) {
        if (kmClipped()) {
            discard;
        }
        vec4 color;
        if (kmMode == 2) {
            color = kmSampleTile();
        } else if (kmMode == 3) {
            vec2 uv = texCoord0;
            if (uv.x < kmTexRect.x || uv.y < kmTexRect.y || uv.x > kmTexRect.z || uv.y > kmTexRect.w) {
                color = vec4(KM_VOID, 1.0);
            } else {
                vec2 atlas = vec2(textureSize(Sampler0, 0));
                color = texelFetch(Sampler0, ivec2(uv * atlas), 0);
            }
        } else if (kmMode == 4) {
            color = vertexColor;
        } else {
            color = texture(Sampler0, texCoord0) * vertexColor;
            int fx = int(kmAux.x + 0.5);
            if (fx == 2 || fx == 3) {
                vec2 dx = dFdx(kmCanvas);
                vec2 dy = dFdy(kmCanvas);
                vec2 qx = dFdx(kmQuad);
                vec2 qy = dFdy(kmQuad);
                float det = qx.x * qy.y - qx.y * qy.x;
                if (abs(det) > 1e-8) {
                    vec2 sizeX = (dx * qy.y - dy * qx.y) / det;
                    vec2 sizeY = (dy * qx.x - dx * qy.x) / det;
                    vec2 size = vec2(sizeX.x, sizeY.y);
                    vec2 tl = kmCanvas - kmQuad * size;
                    vec2 lo = min(tl, tl + size);
                    vec2 hi = max(tl, tl + size);
                    bool inside = kmCursor.x >= lo.x && kmCursor.y >= lo.y && kmCursor.x <= hi.x && kmCursor.y <= hi.y;
                    if (fx == 2 && inside) {
                        color.rgb = min(color.rgb * 1.18 + vec3(0.06), vec3(1.0));
                    }
                    if (fx == 3 && !inside) {
                        discard;
                    }
                }
            }
        }
        if (color.a < 0.1) {
            discard;
        }
        fragColor = color;
        return;
    }

#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
