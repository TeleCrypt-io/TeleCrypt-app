<template>
    <div class="container">
        <div class="card-caption-1">Tammy is open source</div>
        <div class="card-caption-2">
            Tammy is built on <a href="https://trixnity.gitlab.io/trixnity/">Trixnity</a> and <a
                href="https://matrix.org/">Matrix</a> and so is the code base of Tammy open.
        </div>
        <br />
        <div class="button">
            <v-btn rounded="xl" size="x-large" block @click="onSource">
                Tammy on Gitlab
            </v-btn>
        </div>
        <br />
        <br />
        <br />
        <div class="card-caption-1">Contact Us</div>
        <div class="card-caption-2">
            If you have questions, feedback or want to work with us to realize your own project,
            you can contact us via <a>kontakt@connect2x.de</a>
        </div>
        <br />
        <div class="button">
            <v-btn rounded="xl" size="x-large" block @click="onContact">
                Send a Request
            </v-btn>
        </div>
    </div>
</template>


<script setup lang="ts">
function onContact() {
    console.log("contact")
    window.open('mailto:kontakt@connect2x.de');
}
function onSource() {
    window.open('https://gitlab.com/connect2x/tammy')
}
</script>


<style scoped>
.container {
    margin-bottom: 128px;
    width: 100%;
}

.button {
    width: 128px;
    left: 50%;
    width: 50%;
    margin: 0 auto;
}

.card-caption-1 {
    font-size: 2.4em;
    font-weight: 500;
    width: 50%;
    margin: 0 auto;
}

.card-caption-2 {
    font-size: 1.2em;
    font-weight: 300;
    width: 50%;
    margin: 0 auto;
}
</style>