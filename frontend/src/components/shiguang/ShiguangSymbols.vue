<script setup lang="ts">
// 拾光字标与工具符号：来自冻结参考 index.html 的 symbol 表，全局只渲染一份，供 <use href="#..."> 引用。
</script>

<template>
  <svg class="sgv-symbols" aria-hidden="true">
    <symbol id="sgv-brand-sign" viewBox="0 0 218 88"><g fill="currentColor" stroke="none"><path d="M13 3h10v20h9v9h-9v9l9-4v10l-9 4v22c0 6-3 9-9 9H5L2 72h9c2 0 2-1 2-2V55L1 60V49l12-5V32H1v-9h12Z" /><path d="M58 2h10c5 12 13 21 25 29l-5 9c-11-7-20-16-26-27-5 10-13 20-25 28l-6-8C45 24 53 14 58 2ZM44 34h35v9H44Z" /><path fill-rule="evenodd" d="M40 50h43v31H40Zm10 9v13h23V59Z" /><path d="M139 2h10v32h31v10h-22v24c0 3 1 4 4 4h8c3 0 4-3 4-13l10 4c-1 13-4 19-11 19h-16c-6 0-9-3-9-10V44h-13c-1 18-11 31-31 39l-6-10c17-5 25-15 26-29h-23V34h38ZM109 9l9-5 13 20-9 6ZM166 4l10 5-13 21-8-6Z" /></g><path class="brand-stop" d="M199 68h15v15h-15Z" stroke="none" /></symbol>
    <symbol id="sgv-arrow" viewBox="0 0 24 24"><path d="M5 12h14" /><path d="m12 5 7 7-7 7" /></symbol>
    <symbol id="sgv-diagonal" viewBox="0 0 24 24"><path d="M7 7h10v10" /><path d="M7 17 17 7" /></symbol>
    <symbol id="sgv-back" viewBox="0 0 24 24"><path d="m12 19-7-7 7-7" /><path d="M19 12H5" /></symbol>
    <symbol id="sgv-close" viewBox="0 0 24 24"><path d="M18 6 6 18" /><path d="m6 6 12 12" /></symbol>
    <symbol id="sgv-search" viewBox="0 0 24 24"><path d="m21 21-4.34-4.34" /><circle cx="11" cy="11" r="8" /></symbol>
    <symbol id="sgv-pin" viewBox="0 0 24 24"><path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0" /><circle cx="12" cy="10" r="3" /></symbol>
    <symbol id="sgv-sun" viewBox="0 0 24 24"><circle cx="12" cy="12" r="4" /><path d="M12 2v2" /><path d="M12 20v2" /><path d="m4.93 4.93 1.41 1.41" /><path d="m17.66 17.66 1.41 1.41" /><path d="M2 12h2" /><path d="M20 12h2" /><path d="m6.34 17.66-1.41 1.41" /><path d="m19.07 4.93-1.41 1.41" /></symbol>
    <symbol id="sgv-calendar" viewBox="0 0 24 24"><path d="M8 2v4" /><path d="M16 2v4" /><rect width="18" height="18" x="3" y="4" rx="2" /><path d="M3 10h18" /><path d="M8 14h.01" /><path d="M12 14h.01" /><path d="M16 14h.01" /><path d="M8 18h.01" /><path d="M12 18h.01" /><path d="M16 18h.01" /></symbol>
    <symbol id="sgv-check" viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></symbol>
    <symbol id="sgv-eye" viewBox="0 0 24 24"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0" /><circle cx="12" cy="12" r="3" /></symbol>
    <symbol id="sgv-bell" viewBox="0 0 24 24"><path d="M10.268 21a2 2 0 0 0 3.464 0" /><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326" /></symbol>
    <symbol id="sgv-heart" viewBox="0 0 24 24"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z" /></symbol>
    <symbol id="sgv-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></symbol>
    <symbol id="sgv-shield" viewBox="0 0 24 24"><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z" /><path d="m8 12 3 3 5-6" /></symbol>
    <symbol id="sgv-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /><path d="M12 11v6M12 7h.01" /></symbol>
    <symbol id="sgv-reload" viewBox="0 0 24 24"><path d="M20 7v5h-5M20 12a8 8 0 1 0-2 6" /></symbol>
    <symbol id="sgv-wifi" viewBox="0 0 24 24"><path d="M3 9a15 15 0 0 1 18 0M6 12a10 10 0 0 1 12 0M9 15a5 5 0 0 1 6 0M12 18h.01" /></symbol>
    <symbol id="sgv-cup" viewBox="0 0 24 24"><path d="M5 7h12v8a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5ZM17 8h2a3 3 0 0 1 0 6h-2M9 2v2M13 2v2" /></symbol>
    <symbol id="sgv-bag" viewBox="0 0 24 24"><rect x="4" y="7" width="16" height="14" rx="2" /><path d="M8 7V5a4 4 0 0 1 8 0v2M9 12h6" /></symbol>
    <symbol id="sgv-light" viewBox="0 0 24 24"><path d="M8 15c-5-4-2-12 4-12s9 8 4 12l-1 3H9ZM9 21h6M10 10l2 3 2-3M12 13v5" /></symbol>
  </svg>
</template>

<style scoped>
.sgv-symbols {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
}
</style>
