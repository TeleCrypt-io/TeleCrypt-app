import { createI18n } from 'vue-i18n'


export default createI18n({
    legacy: false,
    locale: 'en',
    messages: {
        en: {
            hero: {
                caption3: 'A robust and secure\ncross platform messenger\nutilizing the\nMatrix protocol.',
                getTammy: 'Get Tammy',
            },
            features: {
                card1: {
                    title: 'FAST',
                    details: 'A messenger that feels like a native app on desktop and mobile by building on Compose Multiplatform.',
                },
                card2: {
                    title: 'SIMPLE',
                    details: 'Intuitive UI with a modern look and explanatory guides within the app.',
                },
                card3: {
                    title: 'SECURE',
                    details: 'Suitable for critical communication with the Matrix messenger protocol, Single Sign On and E2E encryption at the core.',
                },
                card4: {
                    title: 'EXTENSIBLE',
                    details: 'Developers can start with an out of the box working messenger that can be modified to their requirements.',
                },
            },
        },
        de: {
            hero: {
                caption3: 'Ein robuster und sicherer\ncross-platform Messenger, der auf dem\nMatrix-Protocol aufbaut.',
                getTammy: 'Hol\' dir Tammy',
            },
            features: {
                card1: {
                    title: 'SCHNELL',
                    details: 'Ein sich nativ anfühlender Messenger ob auf Desktop oder Mobil durch das Aufbauen auf Compose Multiplatform.',
                },
                card2: {
                    title: 'EINFACH',
                    details: 'Intuitives und modernes UI mit schritt-für-schritt Einleitungsprozessen.',
                },
                card3: {
                    title: 'SICHER',
                    details: 'Geeignet für kritische Kommunikation mit dem Matrix-Protokol, Single-Sign-On und E2E-Verschlüsselung als Basis.',
                },
                card4: {
                    title: 'ANPASSBAR',
                    details: 'Entwickler können sofort mit einem Funktionierenden Projekt starten und es deren Anforderungen anpassen.',
                },
            },
        }
    }
})