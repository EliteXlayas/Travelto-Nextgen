#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 localPos;
layout(location = 1) flat in vec4 shape;
layout(location = 2) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    float radius = localPos.x > 0.0 ? max(shape.z, 0.0) : abs(shape.z);
    vec2 edge = abs(localPos) - shape.xy + radius;
    float distanceToEdge = length(max(edge, 0.0)) + min(max(edge.x, edge.y), 0.0) - radius;
    float aa = max(fwidth(distanceToEdge) * 0.5, 0.001);
    float coverage = 1.0 - smoothstep(-aa, aa, distanceToEdge);
    if (shape.w > 0.0) {
        coverage = smoothstep(-aa, aa, distanceToEdge + shape.w) - (1.0 - coverage);
    } else if (shape.w < 0.0) {
        coverage = 1.0 - smoothstep(-aa, -shape.w + aa, distanceToEdge);
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * coverage);
}
