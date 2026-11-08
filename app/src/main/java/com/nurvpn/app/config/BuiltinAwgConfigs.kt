package com.nurvpn.app.config

object BuiltinAwgConfigs {

    val AMNEZIA_1: String = """[Interface]
Address = 172.16.0.2
MTU = 1280
PrivateKey = pVx1J46LOHdvnBVT4JGSyzFX9bihA6f+7TdV6zDqmxI=
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e300d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a4d61782d466f7277617264733a2037300d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e746163743a203c7369703a616c69636540706333332e61746c616e74612e636f6d3e0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f7364700d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>
I2 = <b 0x5349502f322e302031303020547279696e670d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>

[Peer]
Endpoint = 162.159.192.38:1074
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 25"""

    val AMNEZIA_2: String = """[Interface]
Address = 172.16.0.2, 2606:4700:110:8915:6542:5487:4c8a:88dc
MTU = 1280
PrivateKey = xUQ2W9wWbkydQRNEtehnJWTo73SNebzowr5uBhljerI=
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4

[Peer]
Endpoint = engage.cloudflareclient.com:864
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 25"""

    val AMNEZIA_3: String = """[Interface]
Address = 172.16.0.2, 2606:4700:110:86fb:bdae:3e42:1356:8e8d
MTU = 1380
PrivateKey = 35EbSQSll0ixOC/Gp/MqPkZKxmwtRMklU8tKpSqXGrw=
Jc = 5
Jmin = 50
Jmax = 100
H1 = 10
H2 = 20
H3 = 30
H4 = 40

[Peer]
Endpoint = 188.114.99.157:1074
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
PersistentKeepalive = 25"""

    val MASQUE_1: String = """PrivateKey = MHcCAQEEIOW4vwfQkgRj5b8jtZ4QBewVHKCoVy7R77gi6cda/8SwoAoGCCqGSM49AwEHoUQDQgAEaFQYv+mbnX+WrAjprlV7PqanqYtPWsR18y/9tQy8U1Z4rO4nwsIPpuHoj50Nkow3jXqSsA+Ix+mXqFERojs0ug==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:87f6:1d61:27a9:514e:1d01
MTU = 1360
Server = 162.159.198.2
Port = 443
SNI = 4pda.to
Network = h2
Profile = cloudflare"""

    val MASQUE_2: String = """PrivateKey = MHcCAQEEIH3YOD6GFFhzFGHuuFORpTA1XDDolMM1xw6ZCZ/aofdeoAoGCCqGSM49AwEHoUQDQgAEyqs+pwxpdKCRqQI/Z6pewjJATnFDjUic5YXyn0r9tHhVsca04ngNd2CIDAbUxk7AOjjkdTbNO6P16MQuQq3LDw==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:88a9:f217:e658:f564:1e6a
MTU = 1360
Server = 162.159.199.2
Port = 443
SNI = mail.ru
Network = h2
Profile = cloudflare"""

    val MASQUE_3: String = """PrivateKey = MHcCAQEEIOnBcMKNQF0KjQm2wFWIq7G/qViEhi/B6IaHGTXWKm1aoAoGCCqGSM49AwEHoUQDQgAEz6ckVCimW7H4kdGyuj7ODxLg+amWldE40MWHcDEbHUwxsr5nqvycm7FYif2Qs2r1PJvpo2/F6KScTh9xFIDtKQ==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8794:3ea6:1f2a:7af:5011
MTU = 1420
Server = 162.159.198.2
Port = 443
SNI = 4pda.to
Network = h2
Profile = cloudflare"""

    val MASQUE_4: String = """PrivateKey = MHcCAQEEIHN68+3TVgtPMSPcU5tyvy9+/iYyx2OSA3BPJL7VtrV0oAoGCCqGSM49AwEHoUQDQgAEmvCvJiY2fyH1/YEk0dj0BLNK3VotAwiA4UDytOFZ+c5qp87kXYEaCEYCYzofh1839Yo0TE/VXMdHlHMGGg124A==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8018:9c83:ab35:ee51:6294
MTU = 1360
Server = 162.159.198.2
Port = 443
SNI = 4pda.to
Network = h3
Profile = cloudflare"""

    val MASQUE_5: String = """PrivateKey = MHcCAQEEICGfgyMaCjNIqxqOKNWsiWpSvYBD5q8ijggQK61lrnvDoAoGCCqGSM49AwEHoUQDQgAE6I1efvaN5EbKlwFT1lgxAoosX6Sflf73DfXDMks7poxUvbyX+2VqOv+ffSp7sbk/rNCWeAOrganNgJUR64MybA==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8ae6:a560:5474:e92e:b78b
MTU = 1360
Server = 162.159.199.2
Port = 443
SNI = mail.ru
Network = h3
Profile = cloudflare"""

    val MASQUE_6: String = """PrivateKey = MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/J6ydI7iSZRmv3ymZoAoGCCqGSM49AwEHoUQDQgAEcQfc8lrqDeNvzXA+9rCrPd1BUpqhu9ehtAMQQIL1sdQI8wJGOaf7+jyhPQ8oguczV1UthXd0QeecnF4SAzjSog==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8a8b:5a75:3d7f:af48:303b
MTU = 1360
Server = 162.159.197.2
Port = 443
SNI = 4pda.to
Network = h3
Profile = cloudflare"""

    val MASQUE_7: String = """PrivateKey = MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/J6ydI7iSZRmv3ymZoAoGCCqGSM49AwEHoUQDQgAEcQfc8lrqDeNvzXA+9rCrPd1BUpqhu9ehtAMQQIL1sdQI8wJGOaf7+jyhPQ8oguczV1UthXd0QeecnF4SAzjSog==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8a8b:5a75:3d7f:af48:303b
MTU = 1360
Server = 162.159.197.1
Port = 443
SNI = 4pda.to
Network = h3
Profile = cloudflare"""

    val MASQUE_8: String = """PrivateKey = MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/J6ydI7iSZRmv3ymZoAoGCCqGSM49AwEHoUQDQgAEcQfc8lrqDeNvzXA+9rCrPd1BUpqhu9ehtAMQQIL1sdQI8wJGOaf7+jyhPQ8oguczV1UthXd0QeecnF4SAzjSog==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8a8b:5a75:3d7f:af48:303b
MTU = 1360
Server = 162.159.197.2
Port = 443
SNI = 4pda.to
Network = h2
Profile = cloudflare"""

    val MASQUE_9: String = """PrivateKey = MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/J6ydI7iSZRmv3ymZoAoGCCqGSM49AwEHoUQDQgAEcQfc8lrqDeNvzXA+9rCrPd1BUpqhu9ehtAMQQIL1sdQI8wJGOaf7+jyhPQ8oguczV1UthXd0QeecnF4SAzjSog==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8a8b:5a75:3d7f:af48:303b
MTU = 1360
Server = 162.159.197.5
Port = 443
SNI = 4pda.to
Network = h2
Profile = cloudflare"""

    val MASQUE_10: String = """PrivateKey = MHcCAQEEIDNvVxQi6f70IY5ZXe57iwAxjLJRMA2ZqPWVHIfQcLCEoAoGCCqGSM49AwEHoUQDQgAEJXErBnXsRWEgVDgjz3CgZlc62HW2LovHdWLmeMqr8Yt9uYJx4ur3NhVM0100W9zyp7LLVTf/dTxb2MlFFK2T+g==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8cfe:cd54:bba3:2914:42c1
MTU = 1360
Server = 162.159.198.2
Port = 443
SNI = 4pda.to
Network = h2
Profile = cloudflare"""

    val MASQUE_11: String = """PrivateKey = MHcCAQEEIDNvVxQi6f70IY5ZXe57iwAxjLJRMA2ZqPWVHIfQcLCEoAoGCCqGSM49AwEHoUQDQgAEJXErBnXsRWEgVDgjz3CgZlc62HW2LovHdWLmeMqr8Yt9uYJx4ur3NhVM0100W9zyp7LLVTf/dTxb2MlFFK2T+g==
PublicKey = MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==
Address = 172.16.0.2, 2606:4700:110:8cfe:cd54:bba3:2914:42c1
MTU = 1360
Server = 162.159.199.2
Port = 443
SNI = mail.ru
Network = h2
Profile = cloudflare"""

    val ALL: List<Pair<String, String>> = listOf(
        "amnezia_1" to AMNEZIA_1,
        "amnezia_2" to AMNEZIA_2,
        "amnezia_3" to AMNEZIA_3,
        "masque_1" to MASQUE_1,
        "masque_2" to MASQUE_2,
        "masque_3" to MASQUE_3,
        "masque_4" to MASQUE_4,
        "masque_5" to MASQUE_5,
        "masque_6" to MASQUE_6,
        "masque_7" to MASQUE_7,
        "masque_8" to MASQUE_8,
        "masque_9" to MASQUE_9,
        "masque_10" to MASQUE_10,
        "masque_11" to MASQUE_11,
    )

    fun byId(id: String): String? = ALL.firstOrNull { it.first == id }?.second
}
