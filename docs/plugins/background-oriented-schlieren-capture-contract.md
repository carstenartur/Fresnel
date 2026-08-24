# BOS target and capture contract

The target generator produces an immutable artifact. A later BOS `CapturePlan` will carry the stable target ID and lowercase SHA-256 for every capture step that depends on it. A configured provider receives the shutter trigger only together with evidence that Fresnel presented that exact target.

The target slice does not mark a session as captured or analysed. Those transitions belong to the durable measurement-session service and require corresponding source assets. Manual upload and remote providers share this contract.
