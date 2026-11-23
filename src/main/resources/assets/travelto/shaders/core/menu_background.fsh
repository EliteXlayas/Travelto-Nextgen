#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = 0.2 * (2.0 * gl_FragCoord.xy - ScreenSize) / max(ScreenSize.y, 1.0);
    vec2 flow = ScreenSize;
    vec4 phase = vec4(1.0, 2.0, 3.0, 0.0);
    vec4 light = phase;
    float amplitude = 0.5;
    vec3 elapsedMillisBytes = floor(vertexColor.rgb * 255.0 + 0.5);
    float time = dot(elapsedMillisBytes, vec3(65536.0, 256.0, 1.0)) * 0.001;

    for (int step = 1; step < 19; step++) {
        float index = float(step);
        float denominator = 0.5 - dot(uv, uv);
        denominator = abs(denominator) < 0.0001 ? (denominator < 0.0 ? -0.0001 : 0.0001) : denominator;
        vec2 wave = sin(1.5 * uv / denominator - 9.0 * uv.yx + time);
        light += (1.0 + cos(phase + time)) / max(length((1.0 + index * dot(flow, flow)) * wave), 0.0001);
        time += 1.0;
        amplitude += 0.03;
        flow = cos(time - 7.0 * uv * pow(amplitude, index)) - 5.0 * uv;
        uv *= mat2(cos(index + 0.02 * time - phase.wxzw * 11.0));
        uv += tanh(40.0 * dot(uv, uv) * cos(100.0 * uv.yx + time)) / 200.0
            + 0.2 * amplitude * uv + cos(4.0 / exp(dot(light, light) / 100.0) + time) / 300.0;
    }

    vec3 glow = (25.6 / (min(light, 13.0) + 164.0 / max(light, vec4(0.0001))) - dot(uv, uv) / 250.0).rgb;
    vec3 color = clamp(vec3(glow.b * 0.35, min(glow.g, glow.b), glow.b * 1.15), 0.0, 1.0);
    fragColor = vec4(color, vertexColor.a);
}
