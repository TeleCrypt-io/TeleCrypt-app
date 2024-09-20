<template>
  <v-app>
    <v-main>
      <Hero :goToDownloadSection="goToDownloadSection" />
      <FeatureCards />
      <DownloadTammy ref="downloadSectionRef" />
      <ContactUs />
    </v-main>
  </v-app>
</template>

<script setup lang="ts">
import Hero from './components/Hero.vue'
import FeatureCards from './components/FeatureCards.vue'
import DownloadTammy from './components/DownloadTammy.vue'
import ContactUs from './components/ContactUs.vue'
</script>

<script lang="ts">
import { ref } from 'vue';
const downloadSectionRef = ref(null);
const goToDownloadSection = () => {
  downloadSectionRef.value?.$el.scrollIntoView({ behavior: "smooth", block: "center" })
}
export default {
  setup() {
    return {
      downloadSectionRef,
      goToDownloadSection,
    }
  }
}
</script>

<style scoped>
.logo {
  height: 6em;
  padding: 1.5em;
  will-change: filter;
  transition: filter 300ms;
}

.logo:hover {
  filter: drop-shadow(0 0 2em #646cffaa);
}

.logo.vue:hover {
  filter: drop-shadow(0 0 2em #42b883aa);
}
</style>
