// Track only GTO's client data task; leave its executor and synchronous server path intact.
function initializeCoreMod() {
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return {
        gto_data_initialization: {
            target: {
                type: 'METHOD',
                'class': 'com.gtocore.data.Data',
                methodName: 'init',
                methodDesc: '()V'
            },
            transformer: function(method) {
                if (method.name !== 'init' || method.desc !== '()V' || (method.access & 8) === 0) {
                    throw new Error('GTO data initialization signature changed');
                }
                var matches = [];
                for (var instruction = method.instructions.getFirst(); instruction !== null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() === 184
                            // GTOCore 26.10.9 renames the executor class and method. Data.init
                            // still submits exactly one Runnable to GTOLib; keep that call intact.
                            && instruction.owner.indexOf('com/gtolib/') === 0
                            && instruction.desc === '(Ljava/lang/Runnable;)V') {
                        matches.push(instruction);
                    }
                }
                if (matches.length !== 1) throw new Error('Expected one GTO data submission, found ' + matches.length);
                method.instructions.insertBefore(matches[0], new MethodInsnNode(184,
                        'com/moakiee/thunderbolt/compat/gto/GtoDataInitialization', 'track',
                        '(Ljava/lang/Runnable;)Ljava/lang/Runnable;', false));
                return method;
            }
        }
    };
}
