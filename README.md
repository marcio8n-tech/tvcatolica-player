# TV Católica Player — ExoPlayer Adaptado para TV Box

App nativo Android com ExoPlayer configurado especialmente para TV Boxes com pouca RAM e internet limitada (4-6 Mbps).

## Diferenças do ExoPlayer padrão

| Configuração | Padrão ExoPlayer | Este app |
|---|---|---|
| Buffer máximo | 50s / 50MB | **15s / 10MB** |
| Buffer mínimo | 15s | **5s** |
| RAM usada | ~50MB | **~10MB** |
| Pre-buffer vizinhos | Não | **Sim (2MB cada)** |
| Reconexão automática | Básica | **Exponencial (10x)** |
| Keepalive | Não | **A cada 25s** |
| Timeout prepare | Não | **18s** |

## Como compilar

```bash
./gradlew assembleRelease
```

O APK será gerado em: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Canais (ordem original do APK)

1. Paróquia Vianney
2. TV Família
3. Milênio TV
4. Canção Nova
5. TV Católica 2
6. **TV Católica** ← canal inicial
7. TV Católica HD
8. Gospel Cartoon
9. Santa Cruz TV
10. TV Horizonte
11. TV Padre Cícero
12. Rede Imaculada
13. TV Pai Eterno

## Controles

- **D-pad ←/→** — trocar canal
- **D-pad ↑/↓** — volume
- **OK/Enter** — mostrar/ocultar lista de canais
- **Back** — fechar lista
- **Swipe esquerda/direita** — trocar canal
- **Toque** — mostrar/ocultar lista
