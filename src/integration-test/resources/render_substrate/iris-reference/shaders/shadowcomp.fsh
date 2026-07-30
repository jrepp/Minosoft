#version 330 core

/* RENDERTARGETS: 0 */
in vec2 finUV;
out vec4 foutColor;
uniform sampler2D shadowcolor0;

void main() {
    foutColor = texture(shadowcolor0, finUV);
}
