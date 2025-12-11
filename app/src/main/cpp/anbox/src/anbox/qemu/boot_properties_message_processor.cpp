/*
 * Copyright (C) 2016 Simon Fels <morphis@gravedo.de>
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3, as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranties of
 * MERCHANTABILITY, SATISFACTORY QUALITY, or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

#include "anbox/qemu//boot_properties_message_processor.h"
#include "anbox/graphics/emugl/DisplayManager.h"
#include "anbox/utils.h"
#include <sys/system_properties.h>

namespace anbox::qemu {
BootPropertiesMessageProcessor::BootPropertiesMessageProcessor(
    const std::shared_ptr<network::SocketMessenger> &messenger)
    : QemudMessageProcessor(messenger) {}

BootPropertiesMessageProcessor::~BootPropertiesMessageProcessor() {}

void BootPropertiesMessageProcessor::handle_command(
    const std::string &command) {
  if (command == "list") list_properties();
}

void BootPropertiesMessageProcessor::list_properties() {
  char memfd_value[PROP_VALUE_MAX];
  __system_property_get("sys.use_memfd", memfd_value);
  std::vector<std::string> properties = {
      // device we're running on
      utils::string_format("ro.sf.lcd_density=%d", static_cast<int>(graphics::emugl::DisplayInfo::get()->getDpi())),
      utils::string_format("ro.ananbox.host.api_level=%d", android_get_device_api_level()),
      utils::string_format("sys.use_memfd=%d", atoi(memfd_value)),
  };

  for (const auto &prop : properties) {
    send_header(prop.length());
    messenger_->send(prop.c_str(), prop.length());
  }

  finish_message();
}
}