import java.util.*;
import java.lang.management.*;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.core.channel.*;
import appeng.api.networking.GridFlags;
import appeng.api.networking.pathing.ChannelMode;
public class AlgorithmComparison {
 static BorrowedCapacityCalculator.Result solve(String algorithm,ChannelStress.Graph g){
  if(algorithm.equals("baseline")){var r=BaselineCapacityCalculator.assignChannels(g.grid,g.sources);return new BorrowedCapacityCalculator.Result(r.channelNodes(),r.networkNodes(),r.nodeFlow(),r.connectionFlow());}
  return BorrowedCapacityCalculator.assignChannels(g.grid,g.sources);
 }
 static ChannelStress.Graph parallel(int routes,boolean shared){var g=new ChannelStress.Graph("parallel-"+routes+"-"+shared,ChannelMode.DEFAULT);var s=g.source();var hub=g.relay();ChannelStress.Node entry=s;if(shared){entry=g.node(new Object(),8);g.link(s,entry);}for(int i=0;i<routes;i++){var path=g.node(new Object(),8);g.link(entry,path);g.link(path,hub);}ChannelStress.branch(g,hub,32,false);g.expected=shared?8:routes*8;return g;}
 static ChannelStress.Graph finiteMesh(int count){var g=ChannelStress.mesh(count);g.name="finite-mesh"; // Rebuild relay owners/capacities while retaining exactly the same incidence graph.
  var out=new ChannelStress.Graph("finite-mesh",ChannelMode.X4);for(var n:g.nodes){var owner=n.getOwner();if(owner instanceof ChannelStress.Source){out.source();}else if(n.hasFlag(GridFlags.REQUIRE_CHANNEL)){out.terminal();}else out.node(new Object(),128,GridFlags.DENSE_CAPACITY);}
  for(var e:g.edges)out.link(out.nodes.get(e.a().id),out.nodes.get(e.b().id));
  // Each of 266 sources has one entry relay, whose split capacity is 128.
  out.expected=Math.min(count,266*128);return out;
 }
 static void correctness(String a,int count){for(int i=0;i<count;i++){var g=ChannelStress.random(i);g.expected=ChannelStress.oracle(g);try{ChannelStress.verify(g,solve(a,g));}catch(AssertionError e){System.out.println("FAIL_SEED "+i+" expected="+g.expected);throw e;}}for(int r=1;r<=2;r++)for(boolean shared:new boolean[]{false,true}){var g=parallel(r,shared);ChannelStress.verify(g,solve(a,g));System.out.println("PARALLEL "+a+" routes="+r+" shared8="+shared+" flow="+g.expected);}System.out.println("ORACLE "+a+" PASS "+count);}

 static void lifecycle(){
  var g=new ChannelStress.Graph("disconnect-reconnect",ChannelMode.DEFAULT);
  var s=g.source();var a=g.node(new Object(),8);var b=g.node(new Object(),8);var hub=g.relay();
  g.link(s,a);g.link(a,hub);g.link(s,b);g.link(b,hub);ChannelStress.branch(g,hub,32,false);
  g.expected=16;ChannelStress.verify(g,solve("current",g));
  var removed=g.edges.get(3);removed.a().remove(removed.gc());removed.b().remove(removed.gc());g.edges.remove(removed);
  g.expected=8;var disconnected=solve("current",g);ChannelStress.verify(g,disconnected);
  if(disconnected.connectionFlow().getInt(removed.gc())!=0)throw new AssertionError("stale connection");
  g.link(b,hub);g.expected=16;ChannelStress.verify(g,solve("current",g));
  for(int c:new int[]{8,32,128}){
   var h=new ChannelStress.Graph("uniform-region-"+c,ChannelMode.X4);
   var root=h.source();var left=h.node(new Object(),c);var right=h.node(new Object(),c);var exit=h.relay();
   h.link(root,left);h.link(left,exit);h.link(root,right);h.link(right,exit);ChannelStress.branch(h,exit,c*3,false);
   h.expected=c*2;ChannelStress.verify(h,solve("current",h));
  }
  System.out.println("LIFECYCLE PASS disconnect16to8 reconnect8to16 uniform8/32/128");
 }
 public static void main(String[] args){CoreConfig.setChannelsPerController(128);ChannelSourceRegistry.registerController("compare",ChannelStress.Source.class);String a=args[0],kind=args[1];int n=Integer.parseInt(args[2]);if(kind.equals("oracle")){correctness(a,n);if(a.equals("current")){ChannelStress.special();lifecycle();}return;}
  var g=switch(kind){case "tree"->ChannelStress.tree(n,false);case "dense"->ChannelStress.tree(n,true);case "mesh"->ChannelStress.mesh(n);case "finite"->finiteMesh(n);case "comb"->ChannelStress.chain(n,true);case "chain"->ChannelStress.chain(n,false);case "weighted"->ChannelStress.weighted(n);case "vanilla"->ChannelStress.vanilla(n);default->throw new IllegalArgumentException();};
  int warm=Integer.parseInt(args[3]),reps=Integer.parseInt(args[4]);long tid=Thread.currentThread().getId();double[] times=new double[reps],alloc=new double[reps];
  long coldStart=System.nanoTime();var result=solve(a,g);double cold=(System.nanoTime()-coldStart)/1e6;int flow=ChannelStress.verify(g,result);System.out.println("COLD "+a+" "+kind+" "+n+" "+cold);result=null;
  for(int i=0;i<warm;i++)ChannelStress.verify(g,solve(a,g));System.gc();
  for(int i=0;i<reps;i++){long bytes=ChannelStress.TM.getThreadAllocatedBytes(tid),t=System.nanoTime();result=solve(a,g);times[i]=(System.nanoTime()-t)/1e6;alloc[i]=(ChannelStress.TM.getThreadAllocatedBytes(tid)-bytes)/1048576.0;flow=ChannelStress.verify(g,result);result=null;}
  System.out.printf(Locale.ROOT,"RESULT {\"algorithm\":\"%s\",\"scenario\":\"%s\",\"parameter\":%d,\"nodes\":%d,\"edges\":%d,\"flow\":%d,\"cold_ms\":%.3f,\"p50_ms\":%.3f,\"max_ms\":%.3f,\"alloc_mib\":%.3f,\"samples_ms\":%s}%n",a,kind,n,g.nodes.size(),g.edges.size(),flow,cold,ChannelStress.pct(times,.5),ChannelStress.pct(times,1),ChannelStress.pct(alloc,.5),Arrays.toString(times));
 }
}
