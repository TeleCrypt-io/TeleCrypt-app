<template>
    <div class="container">
        <div class="card-caption-1">Get Tammy for Desktop or Mobile</div>
        <div class="card-container">
            <div class="row">
                <div class="item">
                    <img :src=platformMacos alt="Platform Icon MacOS" />
                    <div class="card-caption-2">MacOS</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadMacos" disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
                <div class="item">
                    <img :src=platformWindows alt="Platform Icon Windows" />
                    <div class="card-caption-2">Windows</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadWindows"
                            disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
                <div class="item">
                    <img :src=platformLinux alt="Platform Icon Linux" />
                    <div class="card-caption-2">Linux</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadLinux" disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
            </div>
            <div class="row">
                <div class="item">
                    <img :src=platformIos alt="Platform Icon iOS" />
                    <div class="card-caption-2">iOS</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadIos" disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
                <div class="item">
                    <img :src=platformAndroid alt="Platform Icon Android" />
                    <div class="card-caption-2">Android</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadAndroid"
                            disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
                <div class="item">
                    <img :src=platformWeb alt="Platform Icon Web" />
                    <div class="card-caption-2">Web</div>
                    <div class="button">
                        <v-btn class="getTammyButton" rounded="xl" size="x-large" block @click="downloadWeb" disabled>
                            DOWNLOAD
                        </v-btn>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>


<script setup lang="ts">
import { ref, computed } from 'vue'
import platformLinux from '../assets/platform-linux.png'
import platformMacos from '../assets/platform-macos.png'
import platformWindows from '../assets/platform-windows.png'
import platformIos from '../assets/platform-ios.png'
import platformAndroid from '../assets/platform-android.png'
import platformWeb from '../assets/platform-web.png'
function downloadMacos() {
    console.log("download Macos")
}
function downloadWindows() {
    console.log("download Windows")
}
function downloadLinux() {
    console.log("download Linux")
}
function downloadIos() {
    console.log("download Ios")
}
function downloadAndroid() {
    console.log("download Android")
}
function downloadWeb() {
    console.log("download Web")
}
import { useTheme } from 'vuetify'
const theme = useTheme()
const iconTint = computed(() => (!!theme.global.current.value.dark) ? 1 : 0)
</script>


<style scoped>
.container {
    margin-top: 24px;
}

.card-container {
    margin-bottom: 128px;
    width: 100%;
    display: flex;
    flex-flow: row wrap;

    gap: 20px;
    justify-content: center;
    align-items: start;
    align-content: start;
}

.row {
    align-items: start;
    height: fit-content;
    display: flex;
    flex-flow: row wrap;
    justify-content: center;
    gap: 20px;
    margin: 0;
    padding: 0;
    align-content: start;
}

.card-caption-1 {
    font-size: 2.4em;
    font-weight: 500;
}

.item {
    height: 300px;
    width: 256px;
    margin: 0;
    padding: 20px;
    border-radius: 12px;
    background: #99949411;

    .card-caption-2 {
        font-size: 1.2em;
        font-weight: 300;
    }

    >img {
        margin-top: 12px;
        width: 128px;
        height: 128px;
        filter: invert(v-bind('iconTint')) opacity(66%);
    }

    .button {
        margin-top: 12px;
    }
}
</style>