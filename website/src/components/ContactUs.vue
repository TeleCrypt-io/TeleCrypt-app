<template>
    <div class="container">
        <img src="../assets/icon-gitlab.png" class="product-icon" />
        <div class="card-caption-1">{{ $t('imprint.openSource.title') }}</div>
        <div class="card-caption-2">
            Tammy is built on <a href="https://trixnity.gitlab.io/trixnity/">Trixnity</a> and <a
                href="https://matrix.org/">Matrix</a> and so is the code base of Tammy open.
        </div>
        <br />
        <div class="button">
            <v-btn rounded="xl" size="x-large" block @click="onSource">
                {{ $t('imprint.openSource.button') }}
            </v-btn>
        </div>
        <br />
        <br />
        <br />
        <img src="../assets/icon-tammy.png" class="product-icon" />
        <div class="card-caption-1">{{ $t('imprint.contactUs.title') }}</div>
        <div class="card-caption-2">
            If you have questions, feedback or want to work with us to realize your own project,
            you can contact us via <a>kontakt@connect2x.de</a>
        </div>
        <br />
        <div class="button">
            <v-btn rounded="xl" size="x-large" block @click="onContact">
                {{ $t('imprint.contactUs.button') }}
            </v-btn>
        </div>
        <div class="imprint-container">
            <div class="imprint">
                Anbieter nach § 6 des Gesetzes über die Nutzung von Telediensten (TDG)/ Mediendienste-Staatsvertrag
                (MDStV)<br />
                connect2x GmbH<br />
                Geschäftsführerende: Benedict Benken, Michael Thiele<br />
                Kraftwerk Mitte 7<br />
                01067 Dresden<br />
                E-Mail: kontakt@connect2x.de<br />
                Amtsgericht Dresden HRB 43737<br />
            </div>
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
    width: 100%;
}

.button {
    width: 128px;
    left: 50%;
    width: 50%;
    margin: 0 auto;
}

.product-icon {
    scale: 50%;
    transform: translate(0, 100px);
    filter: grayscale(66%) opacity(66%);
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

.imprint-container {
    margin-top: 128px;
    width: 100%;
    /* height: 200px; */
    display: flex;
    flex: auto;
    background: #88888844;
}

.imprint {
    text-align: start;
    font-size: 0.8em;
    font-weight: 150;
    margin: 24px;
}
</style>