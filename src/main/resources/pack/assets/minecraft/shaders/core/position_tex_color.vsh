#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
    int band = int(clamp(floor(GameTime * 4.0), 0.0, 3.0));
    if (band >= 2 && ProjMat[3][3] == 1.0 && ProjMat[1][1] < 0.0) {
        vec2 gui = ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 0.01);
        vec4 p = ModelViewMat * vec4(Position, 1.0);
        vec2 mid = p.xy - floor(gui * 0.5);
        bool crosshair = abs(mid.x) < 10.0 && mid.y > -10.0 && mid.y < 16.0;
        bool hotbar = abs(p.x - gui.x * 0.5) < 125.0 && p.y > gui.y - 66.0;
        bool fullscreen = (abs(p.x) < 0.5 || abs(p.x - gui.x) < 1.0) && (abs(p.y) < 0.5 || abs(p.y - gui.y) < 1.0);
        if (band == 3 || crosshair || hotbar || fullscreen) {
            vertexColor = vec4(0.0);
        }
    }
}
