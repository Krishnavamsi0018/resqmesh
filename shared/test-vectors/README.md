# Test vectors

`packets-v1.json` holds signed sample packets in the version 1 wire format, made from fixed test keys. Every implementation that reads packets must reach the same verdict on each one: `mesh-core` on the phones, the Node backend, and the simulator.

- `ACCEPT`: the packet is valid.
- `REJECT` + reason: it decodes but must be dropped (bad signature, expired, …).
- `MALFORMED` + reason: the binary envelope itself is broken.

The file is generated, not hand-edited. To regenerate it after a format change:

```bash
cd android
./gradlew :mesh-core:writeTestVectors
```

`mesh-core`'s tests fail if the file no longer matches the code. The keys in it are public test keys; never use them in a real build.
