// NeoECO 20.5.0-gto.1 initializes this shared LDLib factory on both physical sides.
// Its client sync reader must not force the server verifier to resolve ClientLevel.
function initializeCoreMod() {
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return {
        neoeco_gto_remote_ui: {
            target: {
                type: 'METHOD',
                'class': 'cn.dancingsnow.neoecoae.compat.ae2.ECORemoteMachineUI$1',
                methodName: 'readHolderFromSyncData',
                methodDesc: '(Lnet/minecraft/network/FriendlyByteBuf;)Lcn/dancingsnow/neoecoae/compat/ae2/ECORemoteMachineUI$Holder;'
            },
            transformer: function(method) {
                var reads = [];
                for (var instruction = method.instructions.getFirst(); instruction !== null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() === 180
                            && instruction.owner === 'net/minecraft/client/Minecraft'
                            && instruction.desc === 'Lnet/minecraft/client/multiplayer/ClientLevel;') {
                        var getter = instruction.getPrevious();
                        if (getter === null || getter.getOpcode() !== 184
                                || getter.owner !== 'net/minecraft/client/Minecraft'
                                || getter.desc !== '()Lnet/minecraft/client/Minecraft;') {
                            throw new Error('NeoECO client-level access changed');
                        }
                        reads.push(instruction);
                    }
                }
                // A fixed upstream build needs no patch. Reject an unexpected partial contract.
                if (reads.length === 0) return method;
                if (reads.length !== 2) throw new Error('Expected two NeoECO client-level reads, found ' + reads.length);
                reads.forEach(function(field) {
                    method.instructions.remove(field.getPrevious());
                    method.instructions.set(field, new MethodInsnNode(184,
                            'com/moakiee/thunderbolt/compat/neoeco/NeoEcoClientLevel', 'current',
                            '()Lnet/minecraft/world/level/Level;', false));
                });
                return method;
            }
        }
    };
}
