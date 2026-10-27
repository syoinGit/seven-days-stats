package com.yuki.rubinco.octarine.web;

import com.yuki.rubinco.octarine.ingest.CoreKeeperImportService;
import com.yuki.rubinco.octarine.ingest.CoreKeeperWorldParser;
import com.yuki.rubinco.octarine.names.CoreKeeperNames;
import com.yuki.rubinco.worldprogress.service.WorldImportRunLog;
import com.yuki.rubinco.worldprogress.service.WorldProgressAdminService;
import com.yuki.rubinco.worldprogress.web.WorldMaintenanceController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/octarine/maintenance")
@PreAuthorize("@adminAccess.currentUserIsAdmin()")
public class OctarineMaintenanceController extends WorldMaintenanceController {
  private final CoreKeeperNames names;

  public OctarineMaintenanceController(WorldProgressAdminService admin, WorldImportRunLog runLog, CoreKeeperNames names) {
    super(CoreKeeperImportService.GAME_ID, "/octarine/maintenance", "octarine-maintenance", admin, runLog);
    this.names = names;
  }

  @Override
  protected void describeParser(Model model) {
    model.addAttribute("parserVersion", CoreKeeperWorldParser.PARSER_VERSION);
    model.addAttribute("namesLoaded", names.loaded());
    model.addAttribute("namesCount", names.count());
    model.addAttribute("containerVersion", CoreKeeperWorldParser.CONTAINER_VERSION);
  }
}
