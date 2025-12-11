#include <SDL2/SDL.h>
#include <SDL2/SDL_syswm.h>
#include "anbox/graphics/emugl/Renderer.h"
#include "anbox/graphics/emugl/RenderApi.h"
#include "anbox/graphics/emugl/RenderControl.h"
#include "anbox/network/published_socket_connector.h"
#include "anbox/qemu/pipe_connection_creator.h"
#include "anbox/runtime.h"
#include "anbox/common/dispatcher.h"
#include "core/posix/signal.h"
#include "anbox/input/manager.h"
#include "anbox/input/device.h"
#include "anbox/graphics/layer_composer.h"

static const int MAX_TRACKING_ID = 10;

void logger_write(const emugl::LogLevel &level, const char *format, ...) {
  (void)level;

  char message[2048];
  va_list args;

  va_start(args, format);
  vsnprintf(message, sizeof(message) - 1, format, args);
  va_end(args);

  switch (level) {
  case emugl::LogLevel::WARNING:
    WARNING("%s", message);
    break;
  case emugl::LogLevel::ERROR:
    ERROR("%s", message);
    break;
  case emugl::LogLevel::FATAL:
    FATAL("%s", message);
    break;
  case emugl::LogLevel::DEBUG:
    DEBUG("%s", message);
    break;
  case emugl::LogLevel::TRACE:
    TRACE("%s", message);
    break;
  default:
    break;
  }
}

int main()
{
    auto trap = core::posix::trap_signals_for_process(
        {core::posix::Signal::sig_term, core::posix::Signal::sig_int});
    trap->signal_raised().connect([trap](const core::posix::Signal &signal) {
      INFO("Signal %i received. Good night.", static_cast<int>(signal));
      trap->stop();
    });
    auto gl_libs = anbox::graphics::emugl::default_gl_libraries();
    emugl_logger_struct log_funcs;
    log_funcs.coarse = logger_write;
    log_funcs.fine = logger_write;
    if (!anbox::graphics::emugl::initialize(gl_libs, &log_funcs, nullptr)) {
        printf("Failed to initialize OpenGL renderer\n");
        return 1;
    }

    auto rt = anbox::Runtime::create();
    auto dispatcher = anbox::common::create_dispatcher_for_runtime(rt);

    anbox::graphics::Rect frame{0, 0, 1024, 768};
    std::uint32_t flags = 0;
    auto window_ = SDL_CreateWindow("AnAnbox Demo",
            frame.left(), frame.top(),
            frame.width(), frame.height(),
            flags);
    SDL_ShowWindow(window_);

    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    SDL_GetWindowWMInfo(window_, &info);

    auto renderer_ = std::make_shared<::Renderer>();
    auto native_window = static_cast<EGLNativeWindowType>(info.info.x11.window); 
    renderer_->initialize(static_cast<EGLNativeDisplayType>(info.info.x11.display));
    registerRenderer(renderer_);
    renderer_->createNativeWindow(native_window);

    auto composer_ = std::make_shared<anbox::graphics::LayerComposer>(renderer_, frame, native_window);
    registerLayerComposer(composer_);

    auto sensors_state = std::make_shared<anbox::application::SensorsState>();
    auto gps_info_broker = std::make_shared<anbox::application::GpsInfoBroker>();


    auto input_manager = std::make_shared<anbox::input::Manager>(rt);

    auto pointer_ = input_manager->create_device();
    pointer_->set_name("anbox-pointer");
    pointer_->set_driver_version(1);
    pointer_->set_input_id({BUS_VIRTUAL, 2, 2, 2});
    pointer_->set_physical_location("none");
    pointer_->set_key_bit(BTN_MOUSE);
    // NOTE: We don't use REL_X/REL_Y in reality but have to specify them here
    // to allow InputFlinger to detect we're a cursor device.
    pointer_->set_rel_bit(REL_X);
    pointer_->set_rel_bit(REL_Y);
    pointer_->set_rel_bit(REL_HWHEEL);
    pointer_->set_rel_bit(REL_WHEEL);
    pointer_->set_prop_bit(INPUT_PROP_POINTER);

    auto keyboard_ = input_manager->create_device();
    keyboard_->set_name("anbox-keyboard");
    keyboard_->set_driver_version(1);
    keyboard_->set_input_id({BUS_VIRTUAL, 3, 3, 3});
    keyboard_->set_physical_location("none");
    keyboard_->set_key_bit(BTN_MISC);
    keyboard_->set_key_bit(KEY_OK);

    auto touch_ = input_manager->create_device();
    touch_->set_name("anbox-touch");
    touch_->set_driver_version(1);
    touch_->set_input_id({BUS_VIRTUAL, 4, 4, 4});
    touch_->set_physical_location("none");
    touch_->set_abs_bit(ABS_MT_SLOT);
    touch_->set_abs_max(ABS_MT_SLOT, 10);
    touch_->set_abs_bit(ABS_MT_TOUCH_MAJOR);
    touch_->set_abs_max(ABS_MT_TOUCH_MAJOR, 127);
    touch_->set_abs_bit(ABS_MT_TOUCH_MINOR);
    touch_->set_abs_max(ABS_MT_TOUCH_MINOR, 127);
    touch_->set_abs_bit(ABS_MT_POSITION_X);
    touch_->set_abs_max(ABS_MT_POSITION_X, frame.width());
    touch_->set_abs_bit(ABS_MT_POSITION_Y);
    touch_->set_abs_max(ABS_MT_POSITION_Y, frame.height());
    touch_->set_abs_bit(ABS_MT_TRACKING_ID);
    touch_->set_abs_max(ABS_MT_TRACKING_ID, MAX_TRACKING_ID);
    touch_->set_prop_bit(INPUT_PROP_DIRECT);

    auto qemu_pipe_connector =
        std::make_shared<anbox::network::PublishedSocketConnector>(
                "./qemu_pipe", rt,
                std::make_shared<anbox::qemu::PipeConnectionCreator>(renderer_, rt, sensors_state, gps_info_broker));

    rt->start();
    trap->run();

    rt->stop();
    renderer_->finalize();
}
