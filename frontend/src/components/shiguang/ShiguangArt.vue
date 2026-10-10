<script setup lang="ts">
import { computed } from 'vue'
import type { ShiguangScene } from './types'

const props = withDefaults(defineProps<{
  scene: ShiguangScene
}>(), {})

let sequence = 0
const identity = `sgv-art-${++sequence}-${Math.random().toString(36).slice(2, 8)}`

// 四组代码手绘静物：形体、比例、笔触、色面与冻结 art.js 逐字节同源（SHIGUANG-S07-20260909）。
const drawings: Record<ShiguangScene, (identity: string) => string> = {
  move: () => `<defs><clipPath id="${identity}-strings"><ellipse cx="232" cy="130" rx="66" ry="88"/></clipPath></defs>
    <path class="floor-line" d="m50 287 142-40 250 55M85 310l108-27 132 33"/>
    <path class="cast-shadow" d="m291 324 47-18c10-16 45-26 77-21 32 5 39 20 16 29-19 8-48 9-73 1l-58 16Z"/>
    <ellipse class="contact-shadow" cx="295" cy="327" rx="17" ry="4"/>
    <ellipse class="ball-shadow cast-shadow" cx="374" cy="316" rx="47" ry="9"/>
    <g class="racket-swing"><g transform="rotate(-24 235 189)">
      <path class="coral outline" d="m200 211 28 52 16 1 28-53-9-6-27 44-27-45Z"/>
      <path class="coral-shade" d="m237 247 7 1 28-37-9-6Z"/>
      <path class="paper outline" d="m228 255 16 1 3 81c-4 10-19 10-23 0Z"/>
      <path class="cool-shade" d="m238 256 6 0 3 81c-2 5-6 7-10 7Z"/>
      <path class="fine-line" d="m227 270 18 5m-18 8 18 5m-18 8 19 5m-20 8 20 5m-20 8 20 5"/>
      <ellipse class="coral-shade outline" cx="236" cy="134" rx="81" ry="104"/>
      <ellipse class="coral outline" cx="232" cy="130" rx="81" ry="104"/>
      <ellipse class="sage outline" cx="232" cy="130" rx="69" ry="91"/>
      <path class="lit-face" d="M231 40c-33 1-63 38-66 77l115-52c-14-17-29-24-49-25Z"/>
      <g class="racket-mesh" clip-path="url(#${identity}-strings)"><path d="M172 34v190m15-190v190m15-190v190m15-190v190m15-190v190m15-190v190m15-190v190m15-190v190m15-190v190m15-190v190M153 55h160M153 70h160M153 85h160M153 100h160M153 115h160M153 130h160M153 145h160M153 160h160M153 175h160M153 190h160M153 205h160"/></g>
      <path class="paper-line" d="M162 123c0-43 24-81 53-87"/>
      <path class="fine-line" d="M252 224c35-12 59-49 61-89"/>
    </g></g>
    <g class="tennis-ball"><circle class="yellow outline" cx="368" cy="271" r="43"/>
      <path class="ball-shade" d="M398 240c21 29 8 72-28 74-15 1-28-6-36-15 38 10 72-17 64-59Z"/>
      <path class="paper-line" d="M334 246c27 9 41 29 43 65m-38-10c13-18 36-28 70-21"/>
      <path class="fine-line" d="m389 247 9 11"/>
    </g>`,
  quiet: () => `
    <path class="cast-shadow" d="m103 310 211-18 142 35-190 17-165-24Z"/>
    <ellipse class="contact-shadow" cx="243" cy="315" rx="140" ry="6"/>
    <path class="lamp-light" d="M292 154h81l83 153c-76 24-163 25-251 3Z"/>
    <g class="book-stack">
      <path class="coral outline" d="M102 273h202c13 0 18 10 18 20v23H102c-25-1-24-42 0-43Z"/>
      <path class="coral-shade" d="M102 308h220v8H102c-12 0-19-10-19-21 3 9 9 13 19 13Z"/>
      <path class="paper outline" d="M104 281h210v26H104c-13 0-14-26 0-26Z"/>
      <path class="cool-shade" d="M287 281h27v26h-27Z"/>
      <path class="fine-line" d="M111 289h183m-183 10h168"/>
      <g transform="rotate(-7 202 262)">
        <path class="blue outline" d="M131 240h168v33H131c-18-2-18-30 0-33Z"/>
        <path class="blue-shade" d="M136 265h163v8H131c-10-2-13-8-13-15 4 5 10 7 18 7Z"/>
        <path class="paper outline" d="M140 247h155v18H140c-9 0-9-18 0-18Z"/>
        <path class="page-light" d="M191 247h104v18h-85Z"/>
        <path class="fine-line" d="M146 256h116"/>
        <path class="page-corner outline" d="M285 247h10v18l-10-8Z"/>
        <path class="coral outline" d="M266 246h14v40l-7-7-7 5Z"/>
      </g>
    </g>
    <g class="reading-lamp">
      <ellipse class="green-shade outline" cx="347" cy="305" rx="66" ry="12"/>
      <ellipse class="green outline" cx="345" cy="300" rx="65" ry="12"/>
      <path class="fine-line light-stroke" d="M291 298c20-8 54-10 82-5"/>
      <path class="heavy-line" d="m349 289-3-130"/>
      <path class="fine-line" d="M356 283c42 15 40 34 77 26"/>
      <g class="lamp-shade">
        <path class="green outline" d="M265 149c-1-46 24-88 64-87 39 0 64 41 64 87Z"/>
        <path class="green-shade" d="M341 65c32 8 52 45 52 84h-42c12-30 6-61-10-84Z"/>
        <path class="lit-face" d="M279 122c6-31 24-49 43-54-22 19-33 41-35 68Z"/>
        <ellipse class="paper outline" cx="329" cy="149" rx="64" ry="13"/>
        <path class="yellow outline" d="M317 150c0 21 25 21 25 0"/>
        <path class="paper-line" d="M282 124c3-25 16-44 32-50"/>
      </g>
    </g>
    <g class="pencil">
      <path class="contact-shadow" d="m71 329 115-12 24-8-23-3-116 13Z"/>
      <path class="yellow outline" d="m64 313 116-12 18 5-16 8-116 11Z"/>
      <path class="paper outline" d="m180 301 18 5-16 8Z"/>
      <path class="fine-line" d="m74 318 106-10m10-4 8 2-7 4"/>
    </g>`,
  water: () => `
    <path class="pool-fill" d="M64 273c62-18 104 23 173 7 69-16 94-13 178 3l-12 42H78Z"/>
    <path class="water-surface" d="M74 298c80-14 87 16 166 0s119-2 169 11l-6 16H78Z"/>
    <ellipse class="water-ripple ripple-back" cx="327" cy="290" rx="44" ry="9"/>
    <ellipse class="water-ripple ripple-front" cx="130" cy="306" rx="51" ry="8"/>
    <path class="water-line" d="M57 302c63-24 126 23 196 0 60-20 95 16 177-2m-345 28c40-13 71 6 99 1m141-5c36-7 58 7 87-1"/>
    <path class="cast-shadow" d="M125 254c36-15 79-7 108 10 39-25 98-26 126-10l73 47c-57 12-101 8-134-4-57 14-121-1-173-43Z"/>
    <g class="swim-goggles" transform="rotate(-12 248 197)">
      <path class="green outline" d="M108 176c-38-7-64-28-48-64 31-65 251-99 335-49 33 20 56 61 10 111l-15-12c34-43 27-63-5-84C318 35 106 67 81 123c-8 19 1 32 30 35Z"/>
      <path class="light-stroke fine-line" d="M91 113c38-47 174-76 257-50"/>
      <path class="coral outline" d="m64 134 24 4-4 29-24-6Z"/>
      <path class="sage outline" d="M104 158c27-26 90-26 112 0 14 15 8 62-14 81-29 25-81 7-98-19-11-18-17-46 0-62Z"/>
      <path class="blue outline" d="M113 168c23-18 70-16 88 2 10 12 3 45-12 57-20 17-59 4-72-16-8-12-14-32-4-43Z"/>
      <path class="blue-shade" d="M202 177c5 15-1 40-16 50-20 14-48 5-61-7 35 7 67-7 77-43Z"/>
      <path class="sage outline" d="M260 159c24-22 84-25 110-2 21 18 13 51-5 73-22 29-68 32-91 10-18-17-30-64-14-81Z"/>
      <path class="blue outline" d="M273 170c21-17 63-18 83-2 13 11 5 35-8 50-16 20-49 24-64 8-14-13-23-45-11-56Z"/>
      <path class="blue-shade" d="M358 177c6 12-3 31-14 43-13 15-42 23-57 8 31 1 58-18 71-51Z"/>
      <path class="green outline" d="M219 174c11-10 24-10 36 1l-3 20c-10-10-18-10-29 0Z"/>
      <path class="paper-line" d="M119 174c12-9 30-10 46-7m120 5c14-10 30-11 45-6"/>
    </g>
    <ellipse class="water-ripple ripple-drop" cx="397" cy="314" rx="19" ry="4"/>
    <path class="blue outline water-drop" d="M393 282c-2 12-10 15-9 22 2 11 18 9 19 0 0-6-6-13-10-22Z"/>`,
  together: () => `
    <path class="cast-shadow" d="M111 288c24-20 102-16 138 1l112-5 86 33c-76 30-217 23-258-5Z"/>
    <g class="cup-left">
      <ellipse class="coral-shade outline" cx="178" cy="291" rx="91" ry="17"/>
      <ellipse class="coral outline" cx="175" cy="286" rx="91" ry="19"/>
      <ellipse class="fine-line" cx="175" cy="285" rx="65" ry="10"/>
      <path class="paper outline" d="M119 187c-46-12-59 49-6 54l5-15c-29-1-21-27 1-21Z"/>
      <path class="paper outline" d="M113 177h123l-12 77c-10 33-82 31-99 4Z"/>
      <path class="cool-shade" d="M203 184h32l-11 70c-5 15-24 22-45 21 27-20 24-55 24-91Z"/>
      <ellipse class="paper outline" cx="174" cy="178" rx="62" ry="19"/>
      <ellipse class="coffee" cx="174" cy="180" rx="47" ry="11"/>
      <path class="coffee-highlight" d="M139 179c12-6 28-7 43-5"/>
      <path class="fine-line" d="m137 211 7 36"/>
      <path class="steam-line steam-left" d="M159 143c-20-24 20-31 2-60"/>
      <path class="steam-line steam-middle" d="M185 145c-12-15 12-25 8-42"/>
    </g>
    <g class="cup-right">
      <ellipse class="green-shade outline" cx="341" cy="310" rx="78" ry="15"/>
      <ellipse class="sage outline" cx="338" cy="305" rx="78" ry="17"/>
      <path class="green outline" d="M382 203c48-2 55 54 8 61l-4-17c24-4 26-28-4-28Z"/>
      <path class="green outline" d="M283 192h107l-6 83c-6 30-81 30-91 1Z"/>
      <path class="green-shade" d="M361 202h28l-5 73c-4 17-30 22-49 23 23-12 29-43 26-96Z"/>
      <ellipse class="sage outline" cx="336" cy="193" rx="54" ry="18"/>
      <ellipse class="coffee" cx="336" cy="195" rx="40" ry="10"/>
      <path class="coffee-highlight" d="M307 195c8-5 23-6 38-4"/>
      <path class="paper-line" d="m300 228 4 34"/>
      <path class="steam-line steam-right" d="M328 161c-18-21 16-29 4-54"/>
    </g>`
}

const svg = computed(() =>
  `<svg class="scene-illustration" viewBox="0 0 500 360" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">${drawings[props.scene](identity)}</svg>`
)
</script>

<template>
  <span class="sgv-art" v-html="svg" />
</template>

<style scoped>
.sgv-art {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
