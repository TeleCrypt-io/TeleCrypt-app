<template>
    <div class="container">
        <div class="card-caption-1" style="max-width: min(60vw, 1000px); margin: 0 auto; word-wrap: break-word;">
            {{ $t('features.title') }}
        </div>
        <br />
        <div class="card-container">
            <div class="row">
                <div class="item">
                    <div class="card-caption-1">{{ $t('features.card1.title') }}</div>
                    <div class="card-caption-2">{{ $t('features.card1.details') }}</div>
                </div>
                <div class="item">
                    <div class="card-caption-1">{{ $t('features.card2.title') }}</div>
                    <div class="card-caption-2">{{ $t('features.card2.details') }}</div>
                </div>
            </div>
            <div class="row">
                <div class="item">
                    <div class="card-caption-1">{{ $t('features.card3.title') }}</div>
                    <div class="card-caption-2">{{ $t('features.card3.details') }}</div>
                </div>
                <div class="item">
                    <div class="card-caption-1">{{ $t('features.card4.title') }}</div>
                    <div class="card-caption-2">{{ $t('features.card4.details') }}</div>
                </div>
            </div>
        </div>
        <br />
        <div class="card-vaption-2" style="max-width: min(60vw, 1000px); margin: 0 auto; word-wrap: break-word;">

        </div>
    </div>
</template>


<style scoped>
.card-container {
    width: 100%;
    display: flex;
    flex-flow: row wrap;

    gap: 20px;
    justify-content: center;
    align-items: start;
    align-content: start;
}

.container {
    margin-bottom: 128px;
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

.item {
    height: 300px;
    width: 256px;
    margin: 0;
    padding: 20px;
    border-radius: 12px;
    background: #848e9716;
}

.card-caption-1 {
    font-size: 2.4em;
    font-weight: 500;
}

.card-caption-2 {
    font-size: 1.2em;
    font-weight: 300;
}
</style>