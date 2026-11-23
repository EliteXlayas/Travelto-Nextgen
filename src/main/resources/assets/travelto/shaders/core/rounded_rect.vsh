#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in ivec2 UV2;
layout(location = 3) in vec4 Color;

layout(location = 0) out vec2 localPos;
layout(location = 1) flat out vec4 shape;
layout(location = 2) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    localPos = UV0;
    float effect = float(UV2.y) / 16.0;
    float pad = effect < 0.0 ? ceil(-effect) + 2.0 : 2.0;
    shape = vec4(abs(UV0) - pad, float(UV2.x) / 16.0, effect);
    vertexColor = Color;
}
