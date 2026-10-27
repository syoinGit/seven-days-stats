package com.yuki.rubinco.luminite.web;

import com.yuki.rubinco.luminite.ingest.TerrariaImportService;
import com.yuki.rubinco.luminite.ingest.TerrariaWorldParser;
import com.yuki.rubinco.luminite.names.TerrariaNames;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldProgressAdminService;
import com.yuki.rubinco.worldprogress.web.WorldMaintenanceController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/luminite/maintenance")
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public class LuminiteMaintenanceController extends WorldMaintenanceController {
  private final TerrariaNames names;

  public LuminiteMaintenanceController(WorldProgressAdminService admin, WorldImportRunLog runLog, TerrariaNames names) {
    super(TerrariaImportService.GAME_ID, "/luminite/maintenance", "luminite-maintenance", admin, runLog);
    this.names = names;
  }

  @Override
  protected void describeParser(Model model) {
    model.addAttribute("parserVersion", TerrariaWorldParser.PARSER_VERSION);
    model.addAttribute("containerVersion", TerrariaWorldParser.SUPPORTED_VERSION);
    model.addAttribute("namesLoaded", names.loaded());
    model.addAttribute("namesItemCount", names.itemCount());
  }
}
