<template>
    <section class="hero">
        <div style="margin-top: 8px;">
            <v-btn-toggle v-model="switchTheme" color="primary" variant="outlined" style="min-width: 200px;">
                <v-btn size="x-small" density="default" width="64">{{ $t('app.theme.dark') }}</v-btn>
                <v-btn size="x-small" density="default" width="64">{{ $t('app.theme.light') }}</v-btn>
            </v-btn-toggle>
            <span style="margin: 24px;" />
            <v-btn-toggle v-model="switchLocale" color="primary" variant="outlined" style="min-width: 200px;">
                <v-btn v-for="locale in $i18n.availableLocales" :key="`locale-${locale}`" :value="locale" size="x-small"
                    density="default" width="48">
                    {{ locale }}
                </v-btn>
            </v-btn-toggle>
        </div>
        <div class="hero-container">
            <div class="preview">
                <img :src=getCurrentPreviewImage() alt="App Preview" />
            </div>
            <div class="title">
                <div>
                    <div class="caption-1">
                        Tammy
                    </div>
                    <div class="caption-2">
                        Messenger
                    </div>
                    <div class="caption-3">
                        {{ $t('hero.caption3') }}
                    </div>
                    <br />
                    <v-btn rounded="xl" size="x-large" color="#3b87af" block @click="scrollToDownload">
                        {{ $t('hero.getTammy') }}
                    </v-btn>
                </div>
            </div>
        </div>
    </section>
</template>


<script lang="ts">
import { useTheme } from 'vuetify'
import { useI18n } from 'vue-i18n'
import previewImageDark from '../assets/app-preview-dark.png'
import previewImageLight from '../assets/app-preview-light.png'
var theme = null;
var i18n = null;

export default {
    props: {
        goToDownloadSection: {
            type: Function,
        },
    },
    setup(props) {
        theme = useTheme()
        i18n = useI18n()
        return {
            getCurrentPreviewImage() {
                return (!!theme.global.current.value.dark) ? previewImageDark : previewImageLight
            },
            getOtherTheme() {
                return (!!theme.global.current.value.dark) ? 'light' : 'dark'
            },
            scrollToDownload() {
                props.goToDownloadSection()
            },
        }
    },
    data() {
        return {
            switchTheme: null,
            switchLocale: null,
        }
    },
    watch: {
        switchTheme(newValue) {
            const newTheme = ['dark', 'light'][newValue]
            console.log("theme: " + newTheme)
            if (!!theme && !!newTheme) theme.global.name.value = newTheme
        },
        switchLocale(newValue) {
            console.log("locale " + newValue)
            if (!!i18n && !!newValue) i18n.locale.value = newValue
        },
    },
    // computed: {
    //     getColorCaption3: () => (!!theme && !!theme.global.current.value.dark) ? '#ff0000' : '#00ff00',
    // },
}
</script>


<style scoped>
.hero {
    width: 100vw;
    background: rgb(var(--v-theme-background));
}

.hero-container {
    margin-top: -200px;
}

.title {
    top: 200px;
    transform: translate(calc(50% + 50vw - 600px), 0);

    position: absolute;

    .caption-1 {
        color: #3b87af;
        font-size: 6.4em;
        font-weight: 500;
    }

    .caption-2 {
        color: #5f5f5f;
        margin-top: -40px;
        font-size: 4em;
        font-weight: 400;
    }

    .caption-3 {
        /* color: v-bind('getColorCaption3'); */
        color: #8c8c8c;
        font-size: 1.6em;
        font-weight: 300;
        width: 300px;
        overflow-wrap: break-word;
    }

    .getTammyButton {
        width: 120px;
        max-width: 300px;
    }
}

.preview {
    width: 100vw;
    height: 1200px;
    box-sizing: border-box;
    display: block;
    margin: 0;

    overflow-clip-margin: content-box;
    overflow: clip;
}

.preview>img {
    pointer-events: none;
    box-sizing: border-box;
    display: block;
    display: block;
    box-sizing: border-box;

    position: static;
    scale: 50%;
    transform: translate(calc(100vw - 1700px), -700px);
}
</style>