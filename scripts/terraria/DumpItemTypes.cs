// Reads item and tile numbers out of your own Terraria install for add-item-categories.py.
// The output is derived from the game, so it is NOT committed. See docs/LUMINITE.md.
//
// Build: mcs -out:DumpItemTypes.exe DumpItemTypes.cs
// Run:   mono DumpItemTypes.exe <Terraria Resources dir> <empty work dir> <itemstats.tsv> <tiles.tsv>
using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;

class DumpItemTypes {
  static readonly string[] Fields = {
    "damage", "melee", "ranged", "magic", "summon", "sentry", "pick", "axe", "hammer",
    "headSlot", "bodySlot", "legSlot", "accessory", "vanity", "createTile", "createWall",
    "consumable", "potion", "healLife", "healMana", "buffType", "ammo", "useAmmo",
    "fishingPole", "bait", "mountType", "shoot", "useStyle", "hairDye", "makeNPC" };
  const BindingFlags Static = BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
  static Assembly game;

  static int Main(string[] args) {
    string dir = args[0];
    // Libraries ship next to the exe or are embedded in it as resources.
    AppDomain.CurrentDomain.AssemblyResolve += (sender, e) => {
      string name = new AssemblyName(e.Name).Name;
      string file = Path.Combine(dir, name + ".dll");
      if (File.Exists(file)) return Assembly.LoadFrom(file);
      string resource = game?.GetManifestResourceNames().FirstOrDefault(r => r.EndsWith("." + name + ".dll"));
      if (resource == null) return null;
      using (var stream = game.GetManifestResourceStream(resource))
      using (var memory = new MemoryStream()) { stream.CopyTo(memory); return Assembly.Load(memory.ToArray()); }
    };
    game = Assembly.LoadFrom(Path.Combine(dir, "Terraria.exe"));
    // Main's static setup needs a save folder; point it at an empty work folder, never the real one.
    game.GetType("Terraria.Program").GetField("SavePath", Static).SetValue(null, Path.GetFullPath(args[1]));

    var itemType = game.GetType("Terraria.Item");
    int count = (short)game.GetType("Terraria.ID.ItemID").GetField("Count").GetValue(null);
    var fields = Fields.Select(f => itemType.GetField(f)).ToArray();
    var setDefaults = itemType.GetMethods().First(m => m.Name == "SetDefaults"
        && m.GetParameters().Length >= 1 && m.GetParameters()[0].ParameterType == typeof(int));
    var items = new StringBuilder("id\t" + string.Join("\t", Fields) + "\n");
    int failed = 0;
    for (int id = 1; id < count; id++) {
      try {
        var item = Activator.CreateInstance(itemType);
        var parameters = setDefaults.GetParameters();
        var call = new object[parameters.Length];
        call[0] = id;
        for (int i = 1; i < parameters.Length; i++) call[i] = parameters[i].HasDefaultValue ? parameters[i].DefaultValue : null;
        setDefaults.Invoke(item, call);
        items.Append(id);
        foreach (var field in fields) {
          object value = field?.GetValue(item);
          items.Append('\t').Append(value is bool b ? (b ? "1" : "0") : Convert.ToString(value));
        }
        items.Append('\n');
      } catch (Exception) {
        failed++; // a few vanity items need a player; they become "other"
      }
    }
    File.WriteAllText(args[2], items.ToString());

    var main = game.GetType("Terraria.Main");
    // Main's tile setup normally runs when the game window starts; run just that part.
    var random = main.GetField("rand", Static);
    if (random.GetValue(null) == null) random.SetValue(null, Activator.CreateInstance(random.FieldType));
    foreach (string setup in new[] { "Initialize_TileAndNPCData1", "Initialize_TileAndNPCData2" }) {
      try { main.GetMethod(setup, Static).Invoke(null, null); }
      catch (Exception e) { Console.Error.WriteLine(setup + " failed: " + (e.InnerException ?? e).GetType().Name); }
    }
    var solid = (bool[])main.GetField("tileSolid").GetValue(null);
    var ore = (bool[])game.GetType("Terraria.ID.TileID+Sets").GetField("Ore").GetValue(null);
    var tiles = new StringBuilder("tile\tsolid\tore\n");
    for (int t = 0; t < solid.Length; t++) tiles.Append(t).Append('\t').Append(solid[t] ? 1 : 0).Append('\t').Append(ore[t] ? 1 : 0).Append('\n');
    File.WriteAllText(args[3], tiles.ToString());
    Console.WriteLine("items " + (count - 1) + ", failed " + failed + ", tiles " + solid.Length
        + ", missing fields: " + string.Join(",", Fields.Where((f, i) => fields[i] == null)));
    return 0;
  }
}
