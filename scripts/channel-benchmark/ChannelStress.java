import appeng.me.*;
import appeng.api.networking.*;
import appeng.api.networking.pathing.*;
import appeng.blockentity.networking.ControllerBlockEntity;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.api.channel.*;
import net.minecraft.core.Direction;
import java.util.*;
import java.lang.reflect.*;
import java.lang.management.*;

/** Synthetic graphs, real AE2 nodes/connections, production assignChannels. */
public class ChannelStress {
 static final int INF=Integer.MAX_VALUE/2;
 static final com.sun.management.ThreadMXBean TM=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 static final Constructor<GridConnection> CONN;
 static {try{CONN=GridConnection.class.getDeclaredConstructor(GridNode.class,GridNode.class,Direction.class);CONN.setAccessible(true);}catch(Exception e){throw new RuntimeException(e);}}
 static class High implements HighCapacityChannelOwner {}
 static class Source extends High {}
 static class Weighted extends High implements ChannelRequestProvider {
  final int request; int used; Weighted(int n){request=n;}
  public int thunderbolt$getRequestedChannels(){return request;}
  public void thunderbolt$setUsedChannels(int n){used=n;}
 }
 static class Wireless extends High implements ConnectionChannelCapacityProvider {
  final int cap;Wireless(int n){cap=n;}public int getConnectionChannelCapacity(ChannelMode mode){return cap;}
 }
 static class Node extends GridNode {
  final int id,cap;
  Node(int id,Object owner,int cap,GridFlags... flags){super(null,owner,(o,n)->{},Set.of(flags));this.id=id;this.cap=cap;}
  public int getMaxChannels(){return cap;}
  void add(GridConnection c){connections.add(c);}
  void remove(GridConnection c){connections.remove(c);}
 }
 record Edge(Node a,Node b,GridConnection gc,int capacity){}
 static class Graph {
  final List<Node> nodes=new ArrayList<>();final List<IGridNode> sources=new ArrayList<>();final List<Edge> edges=new ArrayList<>();
  final Map<Class<?>,List<IGridNode>> machines=new HashMap<>();
  final ChannelMode mode;final IGrid grid; int expected=-1, excluded=0; String name;
  Graph(String name,ChannelMode mode){this.name=name;this.mode=mode;
   IPathingService path=(IPathingService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IPathingService.class},(o,m,a)->switch(m.getName()){case "getChannelMode"->mode;default->throw new UnsupportedOperationException(m.getName());});
   grid=(IGrid)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IGrid.class},(o,m,a)->switch(m.getName()){
    case "getPathingService","getService"->path;case "getMachineClasses"->machines.keySet();case "getMachineNodes"->machines.getOrDefault(a[0],List.of());case "getNodes"->nodes;case "size"->nodes.size();default->throw new UnsupportedOperationException(m.getName());});
  }
  Node node(Object owner,int cap,GridFlags...flags){Node n=new Node(nodes.size(),owner,cap,flags);nodes.add(n);machines.computeIfAbsent(owner.getClass(),k->new ArrayList<>()).add(n);return n;}
  Node relay(){return node(new High(),INF);}
  Node terminal(){return node(new Object(),1,GridFlags.REQUIRE_CHANNEL,GridFlags.CANNOT_CARRY);}
  Node source(){Node n=node(new Source(),INF);sources.add(n);return n;}
  void link(Node a,Node b){link(a,b,false);}
  void link(Node a,Node b,boolean virtual){try{GridConnection gc=CONN.newInstance(a,b,virtual?null:Direction.EAST);a.add(gc);b.add(gc);int cap=INF;if(virtual){if(a.getOwner() instanceof ConnectionChannelCapacityProvider p)cap=Math.min(cap,p.getConnectionChannelCapacity(mode));if(b.getOwner() instanceof ConnectionChannelCapacityProvider p)cap=Math.min(cap,p.getConnectionChannelCapacity(mode));}edges.add(new Edge(a,b,gc,cap));}catch(Exception e){throw new RuntimeException(e);}}
  int demand(){int n=0;for(Node a:nodes)if(a.hasFlag(GridFlags.REQUIRE_CHANNEL))n+=request(a);return n;}
  int maxDegree(){return nodes.stream().mapToInt(n->n.getConnections().size()).max().orElse(0);}
 }
 static int request(Node n){return n.getOwner() instanceof ChannelRequestProvider p?Math.max(1,p.thunderbolt$getRequestedChannels()):1;}
 static int capacity(Node n){if(n.getOwner() instanceof HighCapacityChannelOwner)return INF;if(n.hasFlag(GridFlags.CANNOT_CARRY))return n.hasFlag(GridFlags.REQUIRE_CHANNEL)?1:0;return n.cap;}
 static void branch(Graph g,Node parent,int leaves,boolean dense){if(leaves<=0)return;if(leaves==1){g.link(parent,g.terminal());return;}Node r=dense?g.node(new Object(),32*g.mode.getCableCapacityFactor(),GridFlags.DENSE_CAPACITY):g.relay();g.link(parent,r);for(int i=0;i<4;i++)branch(g,r,leaves/4+(i<leaves%4?1:0),dense);}
 static Graph tree(int count,boolean dense){Graph g=new Graph(dense?"dense-tree":"high-tree",ChannelMode.X4);Node prev=null;for(int i=0;i<266;i++){Node s=g.source();if(prev!=null)g.link(prev,s);prev=s;int n=count/266+(i<count%266?1:0);for(int j=0;j<4;j++)branch(g,s,n/4+(j<n%4?1:0),dense);}g.expected=Math.min(count,136192);return g;}
 static Graph mesh(int count){Graph g=new Graph("mesh",ChannelMode.X4);List<Node> relays=new ArrayList<>();int w=(int)Math.ceil(Math.sqrt(count));for(int i=0;i<count;i++){Node r=g.relay();relays.add(r);g.link(r,g.terminal());if(i%w>0)g.link(r,relays.get(i-1));if(i>=w)g.link(r,relays.get(i-w));}Node prev=null;for(int i=0;i<266;i++){Node s=g.source();if(prev!=null)g.link(prev,s);prev=s;g.link(s,relays.get((int)((long)i*count/266)));}g.expected=Math.min(count,136192);return g;}
 static Object vanillaOwner(){try{var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return ((sun.misc.Unsafe)f.get(null)).allocateInstance(ControllerBlockEntity.class);}catch(Exception e){throw new RuntimeException(e);}}
 static Graph vanilla(int count){Graph g=new Graph("vanilla-faces",ChannelMode.X4);Node prev=null;for(int i=0;i<266;i++){Node s=g.node(vanillaOwner(),INF);g.excluded++;if(prev!=null)g.link(prev,s);prev=s;int n=count/266+(i<count%266?1:0);for(int j=0;j<4;j++)branch(g,s,n/4+(j<n%4?1:0),true);}g.expected=Math.min(count,136192);return g;}
 static Graph weighted(int count){Graph g=new Graph("weighted",ChannelMode.X4);Node prev=null;for(int i=0;i<266;i++){Node s=g.source();if(prev!=null)g.link(prev,s);prev=s;g.link(s,g.node(new Weighted(count),INF,GridFlags.REQUIRE_CHANNEL));}g.expected=Math.min(266*count,136192);return g;}
 static Graph chain(int length,boolean comb){Graph g=new Graph(comb?"comb":"chain",ChannelMode.X4);Node first=g.source(),prev=first;for(int i=1;i<266;i++){Node s=g.source();g.link(prev,s);prev=s;}prev=first;for(int i=0;i<length;i++){Node n=g.relay();g.link(prev,n);prev=n;if(comb)g.link(n,g.terminal());}if(!comb)g.link(prev,g.terminal());g.expected=comb?Math.min(length,136192):1;return g;}
 static Graph random(int seed){Random r=new Random(seed);Graph g=new Graph("oracle-"+seed,ChannelMode.DEFAULT);Node root=g.source();for(int i=0;i<6+r.nextInt(18);i++){Node n=g.node(r.nextBoolean()?new High():new Object(),r.nextBoolean()?8:32);g.link(n,g.nodes.get(r.nextInt(g.nodes.size()-1)));}int relays=g.nodes.size();for(int i=0;i<relays;i++){int a=r.nextInt(relays),b=r.nextInt(relays);if(a!=b)g.link(g.nodes.get(a),g.nodes.get(b));}for(int i=0;i<10+r.nextInt(35);i++){Node n=r.nextBoolean()?g.terminal():g.node(new Weighted(1+r.nextInt(12)),INF,GridFlags.REQUIRE_CHANNEL);g.link(g.nodes.get(r.nextInt(relays)),n);}return g;}
 static int oracle(Graph g){int n=g.nodes.size()*2+2,S=n-2,T=n-1;long[][] residual=new long[n][n];for(Node v:g.nodes){residual[2*v.id][2*v.id+1]+=capacity(v);if(v.hasFlag(GridFlags.REQUIRE_CHANNEL))residual[2*v.id+1][T]+=request(v);}for(Edge e:g.edges){residual[2*e.a.id+1][2*e.b.id]+=e.capacity;residual[2*e.b.id+1][2*e.a.id]+=e.capacity;}for(IGridNode v:g.sources)residual[S][2*((Node)v).id]+=128*g.mode.getCableCapacityFactor();int flow=0;while(true){int[]p=new int[n];Arrays.fill(p,-1);p[S]=S;ArrayDeque<Integer>q=new ArrayDeque<>();q.add(S);while(!q.isEmpty()&&p[T]<0){int a=q.remove();for(int b=0;b<n;b++)if(p[b]<0&&residual[a][b]>0){p[b]=a;q.add(b);}}if(p[T]<0)return flow;long f=INF;for(int a=T;a!=S;a=p[a])f=Math.min(f,residual[p[a]][a]);for(int a=T;a!=S;a=p[a]){residual[p[a]][a]-=f;residual[a][p[a]]+=f;}flow+=f;}}
 static int verify(Graph g,BorrowedCapacityCalculator.Result result){if(result.networkNodes().size()!=g.nodes.size()-g.excluded)throw new AssertionError("discovery "+result.networkNodes().size()+"/"+g.nodes.size());int total=0;for(Node n:g.nodes){int f=result.nodeFlow().getInt(n);if(f<0||f>capacity(n))throw new AssertionError("node capacity "+n.id);if(n.hasFlag(GridFlags.REQUIRE_CHANNEL)){int used=n.getOwner() instanceof Weighted w?w.used:(result.channelNodes().contains(n)?1:0);if(used<0||used>request(n))throw new AssertionError("sink capacity");if(result.channelNodes().contains(n)!=(used==request(n)))throw new AssertionError("winner");if(n.getConnections().size()==1&&f!=used)throw new AssertionError("leaf flow");total+=used;}}for(Edge e:g.edges){int f=result.connectionFlow().getInt(e.gc);if(f<0||f>e.capacity)throw new AssertionError("edge cap");if(e.b.hasFlag(GridFlags.CANNOT_CARRY)&&result.connectionFlow().getInt(e.gc)!=(result.channelNodes().contains(e.b)?1:0))throw new AssertionError("terminal connection");}if(g.expected>=0&&total!=g.expected)throw new AssertionError("flow "+total+" != "+g.expected);return total;}
 static void special(){
  for(boolean virtual:new boolean[]{false,true}){Graph g=new Graph(virtual?"wireless-limit":"physical-bypass",ChannelMode.DEFAULT);Node s=g.source(),a=g.node(new Wireless(16),INF),b=g.node(new Wireless(8),INF);g.link(s,a);g.link(a,b,virtual);branch(g,b,24,false);g.expected=virtual?8:24;verify(g,BorrowedCapacityCalculator.assignChannels(g.grid,g.sources));}
  Graph mb=new Graph("multiblock",ChannelMode.DEFAULT);Node s=mb.source();List<IGridNode> siblings=new ArrayList<>();for(int i=0;i<4;i++){Node d=mb.node(new Object(),1,GridFlags.REQUIRE_CHANNEL,GridFlags.CANNOT_CARRY,GridFlags.MULTIBLOCK);siblings.add(d);mb.link(s,d);}for(var n:siblings)((GridNode)n).addService(IGridMultiblock.class,()->siblings.iterator());mb.expected=1;verify(mb,BorrowedCapacityCalculator.assignChannels(mb.grid,mb.sources));
  Graph barrier=new Graph("cannot-carry",ChannelMode.DEFAULT);s=barrier.source();Node stop=barrier.terminal();barrier.link(s,stop);branch(barrier,stop,4,false);barrier.excluded=barrier.nodes.size()-2;barrier.expected=1;verify(barrier,BorrowedCapacityCalculator.assignChannels(barrier.grid,barrier.sources));
  Graph unlimited=new Graph("infinite",ChannelMode.INFINITE);unlimited.source();if(BorrowedCapacityCalculator.assignChannels(unlimited.grid,unlimited.sources)!=null)throw new AssertionError("infinite");
  Graph repeated=weighted(512);for(int supply:new int[]{128,64,128,32,128}){CoreConfig.setChannelsPerController(supply);repeated.expected=266*supply*4;verify(repeated,BorrowedCapacityCalculator.assignChannels(repeated.grid,repeated.sources));}CoreConfig.setChannelsPerController(128);
  System.out.println("SPECIAL PASS wireless physical multiblock cannot-carry infinite repeated-capacity");
 }
 static long gcTime(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
 static double pct(double[]a,double p){double[]b=a.clone();Arrays.sort(b);return b[(int)Math.ceil(p*b.length)-1];}
 static void benchmark(Graph g,int warmup,int repeats){long id=Thread.currentThread().threadId();BorrowedCapacityCalculator.Result r=null;int total=0;double cold=0;try{long start=System.nanoTime();r=BorrowedCapacityCalculator.assignChannels(g.grid,g.sources);cold=(System.nanoTime()-start)/1e6;total=verify(g,r);System.out.println("COLD_MS "+cold+" flow="+total);for(int i=0;i<warmup;i++){r=null;r=BorrowedCapacityCalculator.assignChannels(g.grid,g.sources);verify(g,r);}r=null;System.gc();long heap0=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();for(var b:ManagementFactory.getMemoryPoolMXBeans())b.resetPeakUsage();long gc0=gcTime();double[]time=new double[repeats],alloc=new double[repeats];for(int i=0;i<repeats;i++){r=null;long b=TM.getThreadAllocatedBytes(id),t=System.nanoTime();r=BorrowedCapacityCalculator.assignChannels(g.grid,g.sources);time[i]=(System.nanoTime()-t)/1e6;alloc[i]=(TM.getThreadAllocatedBytes(id)-b)/1048576.0;total=verify(g,r);}long gc=gcTime()-gc0;long peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(b->b.getType()==MemoryType.HEAP).mapToLong(b->b.getPeakUsage().getUsed()).sum();System.out.printf(Locale.ROOT,"RESULT {\"scenario\":\"%s\",\"nodes\":%d,\"edges\":%d,\"max_degree\":%d,\"sources\":%d,\"demand\":%d,\"flow\":%d,\"winners\":%d,\"cold_ms\":%.3f,\"p50_ms\":%.3f,\"p95_ms\":%.3f,\"max_ms\":%.3f,\"alloc_mib\":%.3f,\"graph_heap_mib\":%.3f,\"heap_peak_pools_mib\":%.3f,\"gc_ms\":%d,\"samples_ms\":%s,\"status\":\"PASS\"}%n",g.name,g.nodes.size(),g.edges.size(),g.maxDegree(),g.sources.size(),g.demand(),total,r.channelNodes().size(),cold,pct(time,.5),pct(time,.95),pct(time,1),pct(alloc,.5),heap0/1048576.0,peak/1048576.0,gc,Arrays.toString(time));}catch(StackOverflowError e){System.out.printf("RESULT {\"scenario\":\"%s\",\"nodes\":%d,\"demand\":%d,\"status\":\"STACK_OVERFLOW\",\"location\":\"%s\"}%n",g.name,g.nodes.size(),g.demand(),e.getStackTrace()[0]);}}
 public static void main(String[]args){CoreConfig.setChannelsPerController(128);ChannelSourceRegistry.registerController("stress",Source.class);String kind=args.length>0?args[0]:"oracle";int n=args.length>1?Integer.parseInt(args[1]):1000;int warm=args.length>2?Integer.parseInt(args[2]):4,repeats=args.length>3?Integer.parseInt(args[3]):12;
  System.out.println("ENV "+System.getProperty("java.version")+" "+System.getProperty("os.arch")+" cpus="+Runtime.getRuntime().availableProcessors()+" args="+ManagementFactory.getRuntimeMXBean().getInputArguments());
  if(kind.equals("oracle")){special();for(int i=0;i<n;i++){Graph g=random(i);g.expected=oracle(g);verify(g,BorrowedCapacityCalculator.assignChannels(g.grid,g.sources));}System.out.println("ORACLE PASS "+n);return;}
  Graph g=switch(kind){case "tree"->tree(n,false);case "dense"->tree(n,true);case "mesh"->mesh(n);case "vanilla"->vanilla(n);case "weighted"->weighted(n);case "chain"->chain(n,false);case "comb"->chain(n,true);default->throw new IllegalArgumentException(kind);};benchmark(g,warm,repeats);
 }
}
