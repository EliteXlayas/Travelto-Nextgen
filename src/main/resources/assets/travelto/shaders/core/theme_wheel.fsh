#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 localPos;
layout(location = 1) flat in vec4 shape;
layout(location = 2) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

vec3 rainbow(float hue) {
    return clamp(abs(fract(hue + vec3(0.0, 0.6666667, 0.3333333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec2 p = localPos / shape.xy;
    float r = length(p);
    float ring = max(r - 0.97, 0.77 - r);
    float square = max(abs(p.x), abs(p.y)) - 0.49;
    float edge = min(ring, square);
    float aa = max(fwidth(edge) * 0.5, 0.0001);
    vec3 color;
    if (ring < square) {
        color = rainbow(atan(p.y, p.x) / 6.2831853);
    } else {
        vec2 sv = clamp(p / 0.98 + 0.5, 0.0, 1.0);
        color = mix(vec3(1.0), vertexColor.rgb, sv.x) * (1.0 - sv.y);
    }
    fragColor = vec4(color, vertexColor.a * (1.0 - smoothstep(-aa, aa, edge)));
}
