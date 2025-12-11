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

#include "anbox/graphics/layer_composer.h"
#include "anbox/graphics/emugl/Renderer.h"
#include "anbox/logger.h"

namespace anbox::graphics {
    /*
LayerComposer::LayerComposer(const std::shared_ptr<Renderer> renderer, const std::shared_ptr<Strategy> &strategy)
    : renderer_(renderer), strategy_(strategy) {}
    */
LayerComposer::LayerComposer(const std::shared_ptr<Renderer> renderer, const std::shared_ptr<graphics::Rect> rect, const EGLNativeWindowType native_window)
    : renderer_(renderer), rect_(rect), native_window_(native_window){}

LayerComposer::~LayerComposer() {}

void LayerComposer::submit_layers(const RenderableList &renderables) {
    renderer_->draw(native_window_, *rect_, renderables);
}
}
