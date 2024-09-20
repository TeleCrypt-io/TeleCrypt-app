import { createI18n } from 'vue-i18n'


export default createI18n({
    legacy: false,
    locale: 'en',
    messages: {
        en: {
            app: {
                theme: {
                    light: "light",
                    dark: "dark",
                },
            },
            hero: {
                caption3: 'A robust and secure\ncross platform messenger\nutilizing the\nMatrix protocol.',
                getTammy: 'Get Tammy',
            },
            features: {
                title: 'Why choose Tammy?',
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
            downloads: {
                title: 'Get Tammy for Desktop or Mobile',
                button: {
                    download: 'DOWNLOAD',
                    soon: 'SOON..',
                    open: 'OPEN',
                },
            },
            imprint: {
                openSource: {
                    title: 'Tammy is Open Source',
                    description: '',
                    button: 'Tammy on Gitlab',
                },
                contactUs: {
                    title: 'Contact Us',
                    description: '',
                    button: 'Send a Request',
                },
            },
        },
        de: {
            app: {
                theme: {
                    light: "hell",
                    dark: "dunkel",
                },
            },
            hero: {
                caption3: 'Ein robuster und sicherer\ncross-platform Messenger, der auf dem\nMatrix-Protocol aufbaut.',
                getTammy: 'Hol\' dir Tammy',
            },
            features: {
                title: 'Weshalb Tammy?',
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
            downloads: {
                title: 'Hole Tammy für Desktop oder Mobile',
                button: {
                    download: 'DOWNLOAD',
                    soon: 'BALD..',
                    open: 'ÖFFNEN',
                },
            },
            imprint: {
                openSource: {
                    title: 'Tammy ist Open Source',
                    description: '',
                    button: 'Tammy auf Gitlab',
                },
                contactUs: {
                    title: 'Kontakt aufnehmen',
                    description: '',
                    button: 'Anfrage senden',
                },
            },
        }
    }
})